package io.sqlguard.starter;

import java.util.concurrent.atomic.AtomicLong;

/** Correlates queries in a synchronous job when there is no HTTP request. */
public final class SqlGuardScope implements AutoCloseable {
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private final Long previous = CURRENT.get();

    private SqlGuardScope() {
        if (previous == null) {
            CURRENT.set(IDS.incrementAndGet());
        }
    }

    public static SqlGuardScope open() {
        return new SqlGuardScope();
    }

    static Long current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
