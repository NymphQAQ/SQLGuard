package io.sqlguard.starter;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sqlguard")
public record SqlGuardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration slowThreshold,
        @DefaultValue("1000") int deepOffset,
        @DefaultValue("5") int nPlusOneThreshold,
        @DefaultValue("4096") int queueCapacity,
        @DefaultValue("1000") int maxFingerprints,
        @DefaultValue("10m") Duration alertCooldown,
        URI reportWebhook,
        URI alertWebhook,
        @DefaultValue Explain explain,
        @DefaultValue Management management) {

    public SqlGuardProperties {
        if (slowThreshold.isNegative() || slowThreshold.isZero() || deepOffset < 1
                || nPlusOneThreshold < 2 || nPlusOneThreshold > 100
                || queueCapacity < 1 || maxFingerprints < 1
                || alertCooldown.isNegative() || alertCooldown.isZero()) {
            throw new IllegalArgumentException("Invalid SQLGuard limits");
        }
        requireHttp(reportWebhook);
        requireHttp(alertWebhook);
    }

    private static void requireHttp(URI uri) {
        if (uri != null && (uri.getHost() == null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))) {
            throw new IllegalArgumentException("SQLGuard webhooks require an HTTP(S) URL");
        }
    }

    public record Explain(@DefaultValue("false") boolean enabled,
                          @DefaultValue("5m") Duration cooldown,
                          @DefaultValue("1000") long scanRows,
                          @DefaultValue("2") int timeoutSeconds) {
        public Explain {
            if (cooldown.isNegative() || cooldown.isZero() || scanRows < 1 || timeoutSeconds < 1) {
                throw new IllegalArgumentException("Invalid SQLGuard EXPLAIN limits");
            }
        }
    }

    public record Management(@DefaultValue("false") boolean enabled, String token) {
        public Management {
            if (enabled && (token == null || token.isBlank())) {
                throw new IllegalArgumentException("Set sqlguard.management.token to enable the management API");
            }
        }
    }
}
