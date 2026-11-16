package io.sqlguard.starter;

import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

record SqlSample(SqlText.Redacted text, String mapper, String operation, String phase, Instant occurredAt,
                 long elapsedNanos, boolean failed, Long scope, Long argumentSignature, ExplainInput explain) {

    record ExplainInput(DataSource dataSource, String sql, List<Object> arguments) {}

    boolean timed() {
        return !"BATCH_ENQUEUE".equals(phase);
    }
}
