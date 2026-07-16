package com.bablsoft.accessflow.proxy.internal;

import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups the statements of a {@code BEGIN…COMMIT} envelope into JDBC-batchable runs (AF-457).
 * A statement is batch-eligible when its row-security rewrite was a no-op (no binds) and it is a
 * simple single-row {@code INSERT INTO t [(cols)] VALUES (...)} whose values are all plain
 * literals; consecutive eligible statements sharing the same table, column list, and arity form
 * one {@link BatchStep} executed as a single {@code PreparedStatement} with
 * {@code addBatch()}/{@code executeLargeBatch()}. Everything else stays a {@link SingleStep} on
 * the existing per-statement path. (A single multi-row {@code VALUES (...),(...)} INSERT is
 * already one statement/one round trip and is deliberately left untouched.)
 */
final class BatchInsertPlanner {

    private BatchInsertPlanner() {
    }

    sealed interface Step permits SingleStep, BatchStep {
    }

    /** Executed on the existing per-statement path with the original rewrite result. */
    record SingleStep(int statementIndex) implements Step {
    }

    /** One parameterized INSERT template plus the literal bind row of each folded statement. */
    record BatchStep(String templateSql, List<List<Object>> rowBinds) implements Step {
    }

    /** A statement's batch-relevant shape; {@code null} when not batch-eligible. */
    private record InsertShape(String table, String columnList, int arity, List<Object> values,
                               String templateSql) {
    }

    /**
     * Plans the execution steps for the envelope's statements. {@code batchable[i]} must be false
     * for any statement whose row-security rewrite produced binds or changed the SQL — those are
     * never folded.
     */
    static List<Step> plan(List<String> statements, boolean[] batchable) {
        var steps = new ArrayList<Step>();
        var shapes = new InsertShape[statements.size()];
        for (int i = 0; i < statements.size(); i++) {
            shapes[i] = batchable[i] ? shapeOf(statements.get(i)) : null;
        }
        int i = 0;
        while (i < statements.size()) {
            var head = shapes[i];
            if (head == null) {
                steps.add(new SingleStep(i));
                i++;
                continue;
            }
            int runEnd = i + 1;
            while (runEnd < statements.size() && sameShape(head, shapes[runEnd])) {
                runEnd++;
            }
            if (runEnd - i >= 2) {
                var rows = new ArrayList<List<Object>>(runEnd - i);
                for (int j = i; j < runEnd; j++) {
                    rows.add(shapes[j].values());
                }
                steps.add(new BatchStep(head.templateSql(), rows));
            } else {
                steps.add(new SingleStep(i));
            }
            i = runEnd;
        }
        return steps;
    }

    private static boolean sameShape(InsertShape head, InsertShape candidate) {
        return candidate != null
                && head.table().equals(candidate.table())
                && head.columnList().equals(candidate.columnList())
                && head.arity() == candidate.arity();
    }

    private static InsertShape shapeOf(String sql) {
        Insert insert;
        try {
            var parsed = CCJSqlParserUtil.parse(sql);
            if (!(parsed instanceof Insert candidate)) {
                return null;
            }
            insert = candidate;
        } catch (Exception ex) {
            return null;
        }
        if (insert.getTable() == null || !(insert.getSelect() instanceof Values values)) {
            return null;
        }
        // Single-row VALUES carry the value expressions directly; multi-row VALUES wrap each row
        // in a ParenthesedExpressionList. Multi-row INSERTs are already one statement/one round
        // trip and stay on the per-statement path.
        var row = values.getExpressions();
        if (row == null || row.isEmpty()
                || row.stream().anyMatch(e -> e instanceof ParenthesedExpressionList<?>)) {
            return null;
        }
        var literals = new ArrayList<Object>(row.size());
        for (Object expression : row) {
            var literal = literalValue((Expression) expression);
            if (literal == NOT_A_LITERAL) {
                return null;
            }
            literals.add(literal);
        }
        String table = insert.getTable().getFullyQualifiedName();
        String columnList = insert.getColumns() == null ? ""
                : insert.getColumns().stream()
                        .map(Column::getFullyQualifiedName)
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
        String template = "INSERT INTO " + table
                + (columnList.isEmpty() ? "" : " (" + columnList + ")")
                + " VALUES (" + "?, ".repeat(literals.size() - 1) + "?)";
        return new InsertShape(table, columnList, literals.size(), literals, template);
    }

    private static final Object NOT_A_LITERAL = new Object();

    private static Object literalValue(Expression expression) {
        return switch (expression) {
            case NullValue ignored -> null;
            case LongValue value -> value.getValue();
            case DoubleValue value -> value.getValue();
            case StringValue value -> value.getValue();
            case BooleanValue value -> value.getValue();
            case DateValue value -> value.getValue();
            case TimeValue value -> value.getValue();
            case TimestampValue value -> value.getValue();
            case SignedExpression signed when signed.getSign() == '-' -> {
                var inner = literalValue(signed.getExpression());
                yield switch (inner) {
                    case Long longValue -> -longValue;
                    case Double doubleValue -> -doubleValue;
                    case null, default -> NOT_A_LITERAL;
                };
            }
            default -> NOT_A_LITERAL;
        };
    }
}
