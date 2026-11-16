package io.sqlguard.starter;

import java.time.Instant;
import java.util.List;

/** The redacted payload shared with reporters and alert receivers. */
public record SqlGuardEvent(
        String fingerprint, String sql, String mapper, String operation, String phase,
        Instant occurredAt, double durationMs, boolean failed, List<Risk> risks) {

    public record Risk(String code, String severity, String title, String detail, String advice) {}
}
