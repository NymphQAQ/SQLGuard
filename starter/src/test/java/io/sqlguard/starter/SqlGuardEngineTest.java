package io.sqlguard.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SqlGuardEngineTest {
    @Test
    void sendsRedactedReportsAndDeduplicatesAlerts() throws Exception {
        var reports = new LinkedBlockingQueue<JsonNode>();
        var alerts = new LinkedBlockingQueue<JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/report", exchange -> receive(reports, exchange));
        server.createContext("/alert", exchange -> receive(alerts, exchange));
        server.start();
        String host = "http://127.0.0.1:" + server.getAddress().getPort();
        try (SqlGuardEngine engine = new SqlGuardEngine(props(URI.create(host + "/report"), URI.create(host + "/alert")), List.of())) {
            engine.submit(new SqlSample(SqlText.redact("UPDATE accounts SET secret='very-private'", i -> null),
                    "AccountMapper.update", "UPDATE", "EXECUTE", Instant.now(), 5_000_000, false, null, null, null));
            engine.submit(new SqlSample(SqlText.redact("UPDATE accounts SET secret='also-private'", i -> null),
                    "AccountMapper.update", "UPDATE", "BATCH_ENQUEUE", Instant.now(), 8_000_000, false, null, null, null));

            JsonNode report = reports.poll(5, TimeUnit.SECONDS);
            JsonNode second = reports.poll(5, TimeUnit.SECONDS);
            JsonNode alert = alerts.poll(5, TimeUnit.SECONDS);
            assertThat(report).isNotNull();
            assertThat(second).isNotNull();
            assertThat(alert).isNotNull();
            assertThat(report.path("sql").asText()).isEqualTo("update accounts set secret = ?");
            assertThat(second.path("fingerprint")).isEqualTo(report.path("fingerprint"));
            assertThat(alert.path("risks").get(0).path("code").asText()).isEqualTo("UNBOUNDED_WRITE");
            assertThat(report.toString() + second + alert).doesNotContain("very-private", "also-private");
            assertThat(engine.store.metrics().executions()).isEqualTo(1);
            assertThat(engine.store.metrics().batchEnqueues()).isEqualTo(1);
            assertThat(engine.store.metrics().alerts()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void isolatesNPlusOneToRepeatedDistinctParametersWithinAScope() throws Exception {
        var events = new LinkedBlockingQueue<SqlGuardEvent>();
        try (SqlGuardEngine engine = new SqlGuardEngine(props(null, null), List.of(events::add))) {
            var text = SqlText.redact("SELECT id FROM users WHERE id = ?", i -> null);
            Long[][] calls = {{1L, 1L}, {1L, 1L}, {2L, 2L}, {1L, 2L}, {1L, 3L}, {1L, 4L}};
            for (Long[] call : calls) {
                engine.submit(new SqlSample(text, "UserMapper.find", "SELECT", "EXECUTE", Instant.now(),
                        1_000_000, false, call[0], call[1], null));
            }
            for (int i = 0; i < calls.length; i++) {
                SqlGuardEvent event = events.poll(5, TimeUnit.SECONDS);
                assertThat(event).isNotNull();
                if (i == 4) {
                    assertThat(event.risks()).extracting(SqlGuardEvent.Risk::code).containsExactly("N_PLUS_ONE");
                } else {
                    assertThat(event.risks()).isEmpty();
                }
            }
        }
    }

    private static void receive(LinkedBlockingQueue<JsonNode> inbox, com.sun.net.httpserver.HttpExchange exchange)
            throws IOException {
        try (exchange) {
            inbox.add(new ObjectMapper().readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(204, -1);
        }
    }

    private static SqlGuardProperties props(URI report, URI alert) {
        return new SqlGuardProperties(true, Duration.ofMillis(50), 1000, 3, 32, 16, Duration.ofMinutes(5),
                report, alert, new SqlGuardProperties.Explain(false, Duration.ofMinutes(1), 100, 2),
                new SqlGuardProperties.Management(false, null));
    }
}
