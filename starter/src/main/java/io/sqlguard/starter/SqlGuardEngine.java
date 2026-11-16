package io.sqlguard.starter;

import static io.sqlguard.starter.SqlGuardEvent.Risk;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SqlGuardEngine implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SqlGuardEngine.class);

    record Health(int queueDepth, int queueCapacity, long droppedSamples, long skippedSamples, long parserFailures,
                  long droppedReports, long deliveryFailures, long skippedPlans, long failedPlans,
                  long slowThresholdMs, boolean explainEnabled) {}

    private record QueryKey(long scope, String fingerprint) {}
    private record AlertKey(String fingerprint, String code) {}
    private record PlanKey(String fingerprint, javax.sql.DataSource source) {}
    private record Analysis(String fingerprint, SqlRules.Shape shape) {}

    final SqlGuardStore store;
    private final SqlGuardProperties config;
    private final ArrayBlockingQueue<SqlSample> inbox;
    private final Cache<String, Analysis> shapes;
    private final Cache<QueryKey, Set<Long>> requestQueries = Caffeine.newBuilder().maximumSize(20_000)
            .expireAfterWrite(Duration.ofMinutes(2)).build();
    private final Cache<AlertKey, Boolean> notified;
    private final Cache<PlanKey, Boolean> planned;
    private final ExecutorService consumer = Executors.newSingleThreadExecutor(r -> worker(r, "sqlguard-analysis"));
    private final ExecutorService parser = boundedWorker("sqlguard-parser", 1);
    private final ThreadPoolExecutor plans;
    private final ThreadPoolExecutor delivery;
    private final List<SqlGuardReporter> reporters;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final MessageDigest digest = newDigest();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder parserFailures = new LongAdder();
    private final LongAdder droppedReports = new LongAdder();
    private final LongAdder deliveryFailures = new LongAdder();
    private final LongAdder skippedPlans = new LongAdder();
    private final LongAdder failedPlans = new LongAdder();
    private volatile boolean running = true;

    SqlGuardEngine(SqlGuardProperties config, List<SqlGuardReporter> reporters) {
        this.config = config;
        this.reporters = reporters;
        this.store = new SqlGuardStore(config.maxFingerprints());
        this.inbox = new ArrayBlockingQueue<>(config.queueCapacity());
        this.shapes = Caffeine.newBuilder().maximumSize(config.maxFingerprints()).build();
        this.notified = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(config.alertCooldown()).build();
        this.planned = Caffeine.newBuilder().maximumSize(config.maxFingerprints())
                .expireAfterWrite(config.explain().cooldown()).build();
        this.plans = boundedWorker("sqlguard-explain", Math.min(128, config.queueCapacity()));
        this.delivery = boundedWorker("sqlguard-delivery", config.queueCapacity());
        this.http = config.reportWebhook() != null || config.alertWebhook() != null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() : null;
        consumer.execute(this::consume);
    }

    boolean canCapture() {
        if (!running || inbox.remainingCapacity() == 0) {
            dropped.increment();
            return false;
        }
        return true;
    }

    void submit(SqlSample sample) {
        if (!running || !inbox.offer(sample)) {
            dropped.increment();
        }
    }

    void skip() {
        skipped.increment();
    }

    Health health() {
        return new Health(inbox.size(), config.queueCapacity(), dropped.sum(), skipped.sum(), parserFailures.sum(),
                droppedReports.sum(), deliveryFailures.sum(), skippedPlans.sum(), failedPlans.sum(),
                config.slowThreshold().toMillis(), config.explain().enabled());
    }

    private void consume() {
        while (running || !inbox.isEmpty()) {
            try {
                SqlSample sample = inbox.poll(150, TimeUnit.MILLISECONDS);
                if (sample != null) {
                    analyze(sample);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                skipped.increment();
                LOG.debug("SQLGuard skipped a sample during analysis");
            }
        }
    }

    private void analyze(SqlSample sample) {
        Analysis analysis = shapes.get(sample.text().sql(), sql -> {
            byte[] hash = digest.digest(sample.text().shape().getBytes(StandardCharsets.UTF_8));
            return new Analysis(HexFormat.of().formatHex(hash, 0, 12),
                    SqlRules.parse(sql, sample.text().shape(), parser));
        });
        var shape = analysis.shape();
        if (!shape.parsed()) {
            parserFailures.increment();
        }
        String id = analysis.fingerprint();
        List<Risk> risks = SqlRules.analyze(sample, shape, config, nPlusOne(sample, shape, id));
        SqlGuardEvent event = new SqlGuardEvent(id, sample.text().shape(), sample.mapper(), sample.operation(),
                sample.phase(), sample.occurredAt(), sample.timed() ? sample.elapsedNanos() / 1_000_000.0 : 0,
                sample.failed(), risks);
        String planStatus = !config.explain().enabled() ? "DISABLED"
                : sample.explain() == null || !shape.explainable() ? "INELIGIBLE" : "PENDING";
        store.record(event, sample.timed(), shape.parsed(), planStatus);
        risks.forEach(risk -> alert(event, risk));
        if (!reporters.isEmpty() || config.reportWebhook() != null) {
            enqueueDelivery(() -> {
                for (SqlGuardReporter reporter : reporters) {
                    try {
                        reporter.report(event);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ignored) {
                        deliveryFailures.increment();
                    }
                }
                post(config.reportWebhook(), event);
            });
        }
        if ("PENDING".equals(planStatus)) {
            schedulePlan(event, sample.explain(), shape);
        }
    }

    private boolean nPlusOne(SqlSample sample, SqlRules.Shape shape, String id) {
        if (!shape.pointQuery() || sample.scope() == null || sample.argumentSignature() == null) {
            return false;
        }
        int threshold = config.nPlusOneThreshold();
        Set<Long> signatures = requestQueries.get(new QueryKey(sample.scope(), id), ignored -> new HashSet<>());
        return signatures.size() < threshold && signatures.add(sample.argumentSignature())
                && signatures.size() == threshold;
    }

    private void schedulePlan(SqlGuardEvent event, SqlSample.ExplainInput input, SqlRules.Shape shape) {
        PlanKey key = new PlanKey(event.fingerprint(), input.dataSource());
        if (planned.asMap().putIfAbsent(key, true) != null) {
            return;
        }
        try {
            plans.execute(() -> {
                MysqlExplain.Result plan = MysqlExplain.run(input, shape, config.explain());
                if ("FAILED".equals(plan.status())) {
                    failedPlans.increment();
                }
                Risk risk = plan.costlyScan() ? new Risk("FULL_SCAN", "MEDIUM", "全表扫描",
                        "执行计划包含达到阈值的 ALL 访问。", "查看执行计划中的候选索引与筛选列。") : null;
                store.plan(event.fingerprint(), plan, risk);
                if (risk != null) {
                    alert(event, risk);
                }
            });
        } catch (RejectedExecutionException ignored) {
            planned.invalidate(key);
            skippedPlans.increment();
            store.plan(event.fingerprint(), MysqlExplain.state("BUSY"), null);
        }
    }

    private void alert(SqlGuardEvent event, Risk risk) {
        if (notified.asMap().putIfAbsent(new AlertKey(event.fingerprint(), risk.code()), true) != null) {
            return;
        }
        store.alert(event, risk);
        enqueueDelivery(() -> {
            LOG.warn("SQLGuard alert fingerprint={} rule={} severity={}", event.fingerprint(), risk.code(), risk.severity());
            post(config.alertWebhook(), new SqlGuardEvent(event.fingerprint(), event.sql(), event.mapper(),
                    event.operation(), event.phase(), event.occurredAt(), event.durationMs(), event.failed(), List.of(risk)));
        });
    }

    private void enqueueDelivery(Runnable task) {
        try {
            delivery.execute(task);
        } catch (RejectedExecutionException ignored) {
            droppedReports.increment();
        }
    }

    private void post(URI url, SqlGuardEvent payload) {
        if (url == null) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) {
                deliveryFailures.increment();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            deliveryFailures.increment();
        }
    }

    @Override
    public void close() {
        running = false;
        stop(consumer);
        stop(parser);
        stop(plans);
        stop(delivery);
    }

    private static void stop(ExecutorService worker) {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadPoolExecutor boundedWorker(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
                task -> worker(task, name));
    }

    private static Thread worker(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
