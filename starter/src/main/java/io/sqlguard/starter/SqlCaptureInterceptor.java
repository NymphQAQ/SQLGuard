package io.sqlguard.starter;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "queryCursor", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class})
})
final class SqlCaptureInterceptor implements Interceptor {
    private static final Pattern SENSITIVE = Pattern.compile(
            "password|passwd|pwd|secret|token|credential|email|phone|mobile|identity|idcard|ssn|creditcard|address",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_ARGUMENTS = 128;

    private final SqlGuardEngine engine;
    private final boolean explainEnabled;

    SqlCaptureInterceptor(SqlGuardEngine engine, SqlGuardProperties properties) {
        this.engine = engine;
        this.explainEnabled = properties.explain().enabled();
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            Object result = invocation.proceed();
            failed = false;
            return result;
        } finally {
            long elapsed = System.nanoTime() - start;
            try {
                if (engine.canCapture()) {
                    capture(invocation, elapsed, failed);
                }
            } catch (RuntimeException ignored) {
                engine.skip();
            }
        }
    }

    private void capture(Invocation invocation, long elapsed, boolean failed) {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        BoundSql sql = handler.getBoundSql();
        MappedStatement mapped = mappedStatement(handler);
        String method = invocation.getMethod().getName();
        boolean query = "query".equals(method) || "queryCursor".equals(method);
        String operation = mapped != null ? mapped.getSqlCommandType().name() : query ? "SELECT" : "WRITE";
        String phase = switch (method) {
            case "batch" -> "BATCH_ENQUEUE";
            case "queryCursor" -> "CURSOR_OPEN";
            default -> "EXECUTE";
        };
        SqlText.Redacted redacted = SqlText.redact(sql.getSql(), i -> {
            if (mapped == null || i >= sql.getParameterMappings().size()) {
                return null;
            }
            ParameterMapping mapping = sql.getParameterMappings().get(i);
            Object value = SENSITIVE.matcher(mapping.getProperty()).find() ? null : argument(mapped, sql, mapping);
            return wholeNumber(value);
        });
        Long scope = query && !failed ? SqlGuardScope.current() : null;
        Long signature = scope != null && mapped != null ? signature(mapped, sql) : null;
        SqlSample.ExplainInput explain = explainEnabled && query && !failed && redacted.explainable()
                ? explainInput(mapped, sql, redacted.binds()) : null;
        engine.submit(new SqlSample(redacted, mapped == null ? "" : mapped.getId(), operation, phase,
                Instant.now(), elapsed, failed, scope, signature, explain));
    }

    private static MappedStatement mappedStatement(StatementHandler handler) {
        Object target = handler;
        while (Proxy.isProxyClass(target.getClass()) && Proxy.getInvocationHandler(target) instanceof Plugin plugin) {
            target = SystemMetaObject.forObject(plugin).getValue("target");
        }
        MetaObject meta = SystemMetaObject.forObject(target);
        if (meta.hasGetter("delegate.mappedStatement")) {
            return (MappedStatement) meta.getValue("delegate.mappedStatement");
        }
        return meta.hasGetter("mappedStatement") ? (MappedStatement) meta.getValue("mappedStatement") : null;
    }

    private static Object argument(MappedStatement mapped, BoundSql sql, ParameterMapping mapping) {
        if (mapping.getMode() != ParameterMode.IN) {
            return null;
        }
        String property = mapping.getProperty();
        if (sql.hasAdditionalParameter(property)) {
            return sql.getAdditionalParameter(property);
        }
        Object original = sql.getParameterObject();
        if (original == null || mapped.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(original.getClass())) {
            return original;
        }
        return mapped.getConfiguration().newMetaObject(original).getValue(property);
    }

    private static Long wholeNumber(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                ? ((Number) value).longValue() : null;
    }

    private static Long signature(MappedStatement mapped, BoundSql sql) {
        List<ParameterMapping> mappings = sql.getParameterMappings();
        if (mappings.isEmpty() || mappings.size() > MAX_ARGUMENTS) {
            return null;
        }
        long hash = 1;
        boolean recognized = false;
        for (ParameterMapping mapping : mappings) {
            Object value = argument(mapped, sql, mapping);
            if (safeScalar(value) || value instanceof UUID || (value instanceof String text && text.length() <= 4096)) {
                hash = 31 * hash + value.hashCode();
                recognized = true;
            } else {
                hash *= 31;
            }
        }
        return recognized ? hash : null;
    }

    private static SqlSample.ExplainInput explainInput(MappedStatement mapped, BoundSql sql, int binds) {
        if (mapped == null || mapped.getStatementType() != StatementType.PREPARED
                || mapped.getConfiguration().getEnvironment() == null || binds > MAX_ARGUMENTS
                || sql.getParameterMappings().size() != binds) {
            return null;
        }
        DataSource dataSource = mapped.getConfiguration().getEnvironment().getDataSource();
        if (dataSource == null || dataSource instanceof AbstractRoutingDataSource) {
            return null;
        }
        List<Object> arguments = new ArrayList<>(binds);
        for (ParameterMapping mapping : sql.getParameterMappings()) {
            if (SENSITIVE.matcher(mapping.getProperty()).find()
                    || mapping.getMode() != ParameterMode.IN
                    || !mapping.getTypeHandler().getClass().getPackageName().equals("org.apache.ibatis.type")) {
                return null;
            }
            Object value = argument(mapped, sql, mapping);
            if (value != null && !safeScalar(value)) {
                return null;
            }
            arguments.add(value);
        }
        return new SqlSample.ExplainInput(dataSource, sql.getSql(), Collections.unmodifiableList(arguments));
    }

    private static boolean safeScalar(Object value) {
        return wholeNumber(value) != null || value instanceof Boolean
                || value instanceof BigDecimal decimal && decimal.getClass() == BigDecimal.class
                        && decimal.precision() <= 65 && decimal.scale() >= -65 && decimal.scale() <= 65
                || value instanceof Float number && Float.isFinite(number)
                || value instanceof Double number && Double.isFinite(number);
    }
}
