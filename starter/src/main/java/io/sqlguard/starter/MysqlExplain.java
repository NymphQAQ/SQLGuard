package io.sqlguard.starter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class MysqlExplain {
    record Step(String table, String accessType, String possibleKeys, String selectedKey, long examinedRows,
                String extra) {}
    record Result(String status, List<Step> steps, List<String> advice, boolean costlyScan) {}

    static Result run(SqlSample.ExplainInput input, SqlRules.Shape shape, SqlGuardProperties.Explain config) {
        try (Connection connection = input.dataSource().getConnection()) {
            if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return state("UNSUPPORTED");
            }
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("EXPLAIN FORMAT=TRADITIONAL " + input.sql())) {
                statement.setQueryTimeout(config.timeoutSeconds());
                statement.setMaxRows(64);
                for (int i = 0; i < input.arguments().size(); i++) {
                    statement.setObject(i + 1, input.arguments().get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    List<Step> steps = new ArrayList<>();
                    while (rows.next()) {
                        steps.add(new Step(rows.getString("table"), rows.getString("type"),
                                rows.getString("possible_keys"), rows.getString("key"),
                                rows.getLong("rows"), rows.getString("Extra")));
                    }
                    return analyze(steps, shape, config.scanRows());
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            return state("FAILED");
        }
    }

    static Result state(String status) {
        return new Result(status, List.of(), List.of(), false);
    }

    static Result analyze(List<Step> steps, SqlRules.Shape shape, long scanRows) {
        Set<String> advice = new LinkedHashSet<>();
        boolean costlyScan = false;
        for (Step step : steps) {
            String table = step.table() == null ? "当前查询" : step.table();
            String extra = step.extra() == null ? "" : step.extra().toLowerCase(Locale.ROOT);
            if ("ALL".equals(step.accessType()) && step.examinedRows() >= scanRows) {
                costlyScan = true;
                if (steps.size() == 1 && !shape.predicateColumns().isEmpty()) {
                    advice.add(table + " 全表扫描。检查筛选列 " + String.join("、", shape.predicateColumns())
                            + " 的索引和选择性。考虑将等值条件列放在复合索引前部。");
                } else {
                    advice.add(table + " 全表扫描。检查 WHERE 筛选列和 JOIN 关联列是否可使用索引。");
                }
                if (step.possibleKeys() != null && step.selectedKey() == null) {
                    advice.add(table + " 有候选索引但未选用。检查列类型、选择性和统计信息。");
                }
            }
            if (extra.contains("using filesort")) {
                if (shape.equalityOnly() && !shape.orderColumns().isEmpty()) {
                    Set<String> candidate = new LinkedHashSet<>(shape.predicateColumns());
                    candidate.addAll(shape.orderColumns());
                    advice.add(table + " 使用额外排序。评估复合索引 (" + String.join(", ", candidate)
                            + ")，并用 EXPLAIN 验证排序方向和预估扫描行数。");
                } else {
                    advice.add(table + " 使用额外排序。检查 ORDER BY 能否利用现有索引。");
                }
            }
            if (extra.contains("using temporary")) {
                advice.add(table + " 使用临时表。检查 GROUP BY / DISTINCT 的覆盖索引和返回列。");
            }
        }
        return new Result("READY", List.copyOf(steps), List.copyOf(advice), costlyScan);
    }
}
