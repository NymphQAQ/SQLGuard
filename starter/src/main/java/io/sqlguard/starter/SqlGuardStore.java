package io.sqlguard.starter;

import static io.sqlguard.starter.SqlGuardEvent.Risk;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SqlGuardStore {
    private static final int TREND_POINTS = 180;
    private static final int SECONDS_PER_POINT = 10;
    private static final int MAX_ALERTS = 100;

    record Metrics(long executions, long slowExecutions, long failures, long fingerprints, long alerts,
                   long batchEnqueues, double averageMs, double p95Ms) {}
    record Point(Instant time, long executions, long slowExecutions, double averageMs, double p95Ms) {}
    record Fingerprint(String id, String sql, String mapper, String operation, long executions,
                       long slowExecutions, long failures, long batchEnqueues, double averageMs, double p95Ms,
                       double maxMs, Instant lastSeen, boolean parsed, List<Risk> risks, String planStatus) {}
    record Detail(Fingerprint fingerprint, MysqlExplain.Result plan) {}
    record Alert(long id, Instant time, String fingerprint, String sql, String mapper, Risk risk) {}

    private final Cache<String, Group> groups;
    private final ArrayDeque<Alert> alerts = new ArrayDeque<>();
    private final Bucket[] buckets = new Bucket[TREND_POINTS];
    private final Latency totals = new Latency(512);
    private long slow;
    private long failures;
    private long batchEnqueues;
    private long alertCount;

    SqlGuardStore(int maxFingerprints) {
        groups = Caffeine.newBuilder().maximumSize(maxFingerprints).build();
    }

    synchronized void record(SqlGuardEvent event, boolean timed, boolean parsed, String planStatus) {
        Group group = groups.get(event.fingerprint(), ignored -> new Group(event, parsed, planStatus));
        group.latest = event;
        group.parsed = parsed;
        event.risks().forEach(risk -> group.risks.put(risk.code(), risk));
        if (event.failed()) {
            group.failures++;
            failures++;
        }
        if (!timed) {
            group.batchEnqueues++;
            batchEnqueues++;
            return;
        }
        double ms = event.durationMs();
        totals.record(ms);
        group.latency.record(ms);
        long tick = tick(event.occurredAt());
        int index = Math.floorMod(tick, buckets.length);
        Bucket bucket = buckets[index];
        if (bucket == null || bucket.tick != tick) {
            bucket = new Bucket(tick);
            buckets[index] = bucket;
        }
        bucket.latency.record(ms);
        if (event.risks().stream().anyMatch(risk -> "SLOW_SQL".equals(risk.code()))) {
            group.slow++;
            bucket.slow++;
            slow++;
        }
    }

    synchronized void plan(String id, MysqlExplain.Result plan, Risk risk) {
        Group group = groups.getIfPresent(id);
        if (group != null) {
            group.plan = plan;
            if (risk != null) {
                group.risks.put(risk.code(), risk);
            }
        }
    }

    synchronized void alert(SqlGuardEvent event, Risk risk) {
        if (alerts.size() == MAX_ALERTS) {
            alerts.removeLast();
        }
        alerts.addFirst(new Alert(++alertCount, Instant.now(), event.fingerprint(), event.sql(), event.mapper(), risk));
    }

    synchronized Metrics metrics() {
        groups.cleanUp();
        return new Metrics(totals.count, slow, failures, groups.estimatedSize(), alertCount,
                batchEnqueues, totals.average(), totals.p95());
    }

    synchronized List<Point> trend(int minutes) {
        int points = minutes * 60 / SECONDS_PER_POINT;
        long now = tick(Instant.now());
        List<Point> result = new ArrayList<>(points);
        for (long tick = now - points + 1; tick <= now; tick++) {
            Bucket bucket = buckets[Math.floorMod(tick, buckets.length)];
            Instant time = Instant.ofEpochSecond(tick * SECONDS_PER_POINT);
            result.add(bucket == null || bucket.tick != tick ? new Point(time, 0, 0, 0, 0)
                    : new Point(time, bucket.latency.count, bucket.slow, bucket.latency.average(), bucket.latency.p95()));
        }
        return result;
    }

    synchronized List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    synchronized List<Fingerprint> fingerprints(String filter, boolean slowOnly, String order, int limit) {
        String query = filter.toLowerCase(Locale.ROOT);
        Comparator<Group> comparison = switch (order) {
            case "latency" -> Comparator.comparingDouble(group -> group.latency.maximum);
            case "count" -> Comparator.comparingLong(group -> group.latency.count);
            default -> Comparator.comparing(group -> group.latest.occurredAt());
        };
        return groups.asMap().values().stream()
                .filter(group -> !slowOnly || group.slow > 0)
                .filter(group -> query.isBlank() || group.latest.sql().toLowerCase(Locale.ROOT).contains(query)
                        || group.latest.mapper().toLowerCase(Locale.ROOT).contains(query)
                        || group.latest.fingerprint().contains(query))
                .sorted(comparison.reversed().thenComparing(group -> group.latest.fingerprint()))
                .limit(limit).map(Group::view).toList();
    }

    synchronized Detail detail(String id) {
        Group group = groups.getIfPresent(id);
        return group == null ? null : new Detail(group.view(), group.plan);
    }

    private static long tick(Instant time) {
        return time.getEpochSecond() / SECONDS_PER_POINT;
    }

    private static final class Group {
        private final Latency latency = new Latency(256);
        private final Map<String, Risk> risks = new LinkedHashMap<>();
        private SqlGuardEvent latest;
        private MysqlExplain.Result plan;
        private boolean parsed;
        private long slow;
        private long failures;
        private long batchEnqueues;

        private Group(SqlGuardEvent event, boolean parsed, String planStatus) {
            this.latest = event;
            this.parsed = parsed;
            this.plan = MysqlExplain.state(planStatus);
        }

        private Fingerprint view() {
            return new Fingerprint(latest.fingerprint(), latest.sql(), latest.mapper(), latest.operation(),
                    latency.count, slow, failures, batchEnqueues, latency.average(), latency.p95(), latency.maximum,
                    latest.occurredAt(), parsed, List.copyOf(risks.values()), plan.status());
        }
    }

    private static final class Bucket {
        private final long tick;
        private final Latency latency = new Latency(128);
        private long slow;

        private Bucket(long tick) {
            this.tick = tick;
        }
    }

    private static final class Latency {
        private final double[] recent;
        private long count;
        private double total;
        private double maximum;

        private Latency(int window) {
            recent = new double[window];
        }

        private void record(double millis) {
            recent[(int) (count % recent.length)] = millis;
            count++;
            total += millis;
            maximum = Math.max(maximum, millis);
        }

        private double average() {
            return count == 0 ? 0 : total / count;
        }

        private double p95() {
            int length = (int) Math.min(count, recent.length);
            if (length == 0) {
                return 0;
            }
            double[] sorted = Arrays.copyOf(recent, length);
            Arrays.sort(sorted);
            return sorted[(int) Math.ceil(0.95 * length) - 1];
        }
    }
}
