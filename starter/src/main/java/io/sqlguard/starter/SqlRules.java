package io.sqlguard.starter;

import static io.sqlguard.starter.SqlGuardEvent.Risk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

final class SqlRules {
    private static final Pattern UNSAFE_PLAN = Pattern.compile(
            "\\bselect\\b.*\\bselect\\b|\\binto\\b|\\bfor (?:update|share)\\b|\\block in share mode\\b|;",
            Pattern.DOTALL);

    record Shape(boolean parsed, boolean write, boolean hasWhere, boolean limited,
                 boolean selectable, boolean pointQuery, boolean explainable, boolean equalityOnly,
                 List<String> predicateColumns, List<String> orderColumns) {}

    static Shape parse(String sql, String canonical, ExecutorService parser) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql, parser,
                    options -> options.withTimeOut(150).withAllowComplexParsing(false));
            if (statement instanceof Update update) {
                return write(update.getWhere(), update.getLimit() != null);
            }
            if (statement instanceof Delete delete) {
                return write(delete.getWhere(), delete.getLimit() != null);
            }
            if (statement instanceof PlainSelect select) {
                boolean hasTable = select.getFromItem() instanceof Table;
                boolean singleTable = hasTable && (select.getJoins() == null || select.getJoins().isEmpty());
                List<String> order = singleTable && select.getOrderByElements() != null
                        ? select.getOrderByElements().stream()
                                .map(item -> item.getExpression())
                                .filter(Column.class::isInstance)
                                .map(column -> ((Column) column).getColumnName())
                                .distinct().limit(4).toList() : List.of();
                Set<String> equality = new LinkedHashSet<>();
                Set<String> range = new LinkedHashSet<>();
                if (singleTable) {
                    collectColumns(select.getWhere(), equality, range);
                }
                Set<String> columns = new LinkedHashSet<>(equality);
                columns.addAll(range);
                boolean explainable = hasTable && !UNSAFE_PLAN.matcher(canonical).find();
                return new Shape(true, false, select.getWhere() != null,
                        select.getLimit() != null || select.getOffset() != null,
                        true, singleTable && !equality.isEmpty() && select.getGroupBy() == null
                                && select.getLimit() == null && select.getOffset() == null,
                        explainable, !equality.isEmpty() && range.isEmpty(), columns.stream().limit(4).toList(), order);
            }
            return new Shape(true, false, false, statement instanceof Select select
                    && (select.getLimit() != null || select.getOffset() != null),
                    statement instanceof Select, false, false, false, List.of(), List.of());
        } catch (JSQLParserException | RuntimeException ignored) {
            return new Shape(false, false, false, false, false, false, false, false, List.of(), List.of());
        }
    }

    static List<Risk> analyze(SqlSample sample, Shape shape, SqlGuardProperties config, boolean nPlusOne) {
        List<Risk> risks = new ArrayList<>();
        if (shape.write() && !shape.limited() && (!shape.hasWhere() || sample.text().alwaysTrue())) {
            risks.add(new Risk("UNBOUNDED_WRITE", "HIGH", "全表写入风险",
                    shape.hasWhere() ? "WHERE 条件恒为真，语句可能影响整张表。" : "UPDATE / DELETE 缺少 WHERE 条件。",
                    "添加明确的 WHERE 条件，先用同条件的 SELECT 核对目标行。"));
        }
        Long offset = sample.text().offset();
        if (shape.selectable() && shape.limited() && offset != null && offset >= config.deepOffset()) {
            risks.add(new Risk("DEEP_PAGINATION", "MEDIUM", "深分页",
                    "OFFSET " + offset + " 达到配置阈值 " + config.deepOffset() + "。",
                    "使用上一页的排序键做游标分页，并为筛选条件和排序键检查复合索引。"));
        }
        if (nPlusOne) {
            risks.add(new Risk("N_PLUS_ONE", "MEDIUM", "疑似 N+1 查询",
                    "同一请求或任务内，相同查询使用至少 " + config.nPlusOneThreshold() + " 组不同参数。",
                    "检查循环和嵌套查询，优先按 ID 批量读取或使用联表查询。"));
        }
        if (sample.timed() && sample.elapsedNanos() >= config.slowThreshold().toNanos()) {
            risks.add(new Risk("SLOW_SQL", "MEDIUM", "慢 SQL",
                    "语句耗时达到 " + config.slowThreshold().toMillis() + " ms 慢 SQL 阈值。",
                    "先查看执行计划，再对照访问类型、预估扫描行数和排序方式。"));
        }
        return List.copyOf(risks);
    }

    private static Shape write(Expression where, boolean limited) {
        return new Shape(true, true, where != null, limited, false, false, false, false, List.of(), List.of());
    }

    private static void collectColumns(Expression expression, Set<String> equality, Set<String> range) {
        if (expression instanceof ParenthesedExpressionList<?> enclosed && enclosed.size() == 1) {
            collectColumns(enclosed.get(0), equality, range);
        } else if (expression instanceof AndExpression and) {
            collectColumns(and.getLeftExpression(), equality, range);
            collectColumns(and.getRightExpression(), equality, range);
        } else if (expression instanceof EqualsTo equal) {
            addColumns(equal.getLeftExpression(), equal.getRightExpression(), equality);
        } else if (expression instanceof Between between && between.getLeftExpression() instanceof Column column) {
            range.add(column.getColumnName());
        } else if (expression instanceof InExpression in && in.getLeftExpression() instanceof Column column) {
            range.add(column.getColumnName());
        } else if (expression instanceof GreaterThan || expression instanceof GreaterThanEquals
                || expression instanceof MinorThan || expression instanceof MinorThanEquals) {
            var binary = (net.sf.jsqlparser.expression.BinaryExpression) expression;
            addColumns(binary.getLeftExpression(), binary.getRightExpression(), range);
        }
    }

    private static void addColumns(Expression left, Expression right, Set<String> columns) {
        if (left instanceof Column column && right instanceof JdbcParameter) {
            columns.add(column.getColumnName());
        } else if (right instanceof Column column && left instanceof JdbcParameter) {
            columns.add(column.getColumnName());
        }
    }
}
