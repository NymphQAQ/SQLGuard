package io.sqlguard.starter;

/** Register as a Spring bean to receive redacted SQL events on the reporting worker. */
@FunctionalInterface
public interface SqlGuardReporter {
    void report(SqlGuardEvent event) throws Exception;
}
