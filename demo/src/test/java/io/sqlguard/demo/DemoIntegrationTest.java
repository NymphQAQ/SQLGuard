package io.sqlguard.demo;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sqlguard.demo.interval-ms=100",
        "sqlguard.slow-threshold=1ms"
})
class DemoIntegrationTest {
    @Autowired
    private TestRestTemplate client;

    @Test
    void autoRegistersInterceptorAndServesOnlyRedactedAuthenticatedResults() {
        var anonymous = client.getForEntity("/sqlguard/api/overview", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var headers = new HttpHeaders();
        headers.setBearerAuth("sqlguard-demo");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var response = client.exchange("/sqlguard/api/overview", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
            JsonNode body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.path("metrics").path("executions").asLong()).isGreaterThan(0);
            assertThat(body.path("metrics").path("slowExecutions").asLong()).isGreaterThan(0);
            assertThat(StreamSupport.stream(body.path("alerts").spliterator(), false)
                    .map(alert -> alert.path("risk").path("code").asText()).toList())
                    .contains("UNBOUNDED_WRITE", "DEEP_PAGINATION", "N_PLUS_ONE", "SLOW_SQL");
            assertThat(body.toString()).doesNotContain("@example.test");
            assertThat(body.path("health").path("parserFailures").asLong()).isZero();
        });
    }

    @Test
    void diagnosesAnActualMysqlPlanWhenConfigured() {
        assumeTrue(Boolean.parseBoolean(System.getenv("SQLGUARD_EXPLAIN"))
                && System.getenv().getOrDefault("SQLGUARD_DB_URL", "").startsWith("jdbc:mysql:"));
        var headers = new HttpHeaders();
        headers.setBearerAuth("sqlguard-demo");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            JsonNode records = client.exchange("/sqlguard/api/fingerprints?q=filterOrders",
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            assertThat(records).isNotNull();
            assertThat(records.size()).isEqualTo(1);
            String id = records.get(0).path("id").asText();
            JsonNode detail = client.exchange("/sqlguard/api/fingerprints/" + id,
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            assertThat(detail).isNotNull();
            assertThat(detail.path("plan").path("status").asText()).isEqualTo("READY");
            assertThat(detail.path("plan").path("steps").get(0).path("accessType").asText()).isEqualTo("ALL");
            assertThat(detail.path("plan").path("advice").toString()).contains("status_code, region_id, total_cents");
        });
    }
}
