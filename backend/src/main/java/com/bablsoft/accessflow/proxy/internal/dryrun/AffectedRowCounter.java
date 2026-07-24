package com.bablsoft.accessflow.proxy.internal.dryrun;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rewrites a plain single-table UPDATE/DELETE into the equivalent {@code SELECT COUNT(*) FROM
 * <target> [WHERE …]} so the proxy can report the exact number of rows the write would affect
 * (issue AF-624) without mutating data. Shapes whose count semantics are not provably identical —
 * joins, {@code UPDATE … FROM}, {@code DELETE … USING} — return empty so the caller degrades to
 * the EXPLAIN estimate. The rewritten SELECT still flows through the row-security rewriter, so the
 * count reflects the governed statement.
 */
public final class AffectedRowCounter {

    private AffectedRowCounter() {
    }

    public static Optional<String> toCountSql(String sql) {
        net.sf.jsqlparser.statement.Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException ex) {
            return Optional.empty();
        }
        return switch (statement) {
            case Update update -> toCountSql(update);
            case Delete delete -> toCountSql(delete);
            default -> Optional.empty();
        };
    }

    private static Optional<String> toCountSql(Update update) {
        if ((update.getJoins() != null && !update.getJoins().isEmpty())
                || (update.getStartJoins() != null && !update.getStartJoins().isEmpty())
                || update.getFromItem() != null || update.getTable() == null) {
            return Optional.empty();
        }
        return Optional.of(countSelect(update.getTable(),
                update.getWhere()));
    }

    private static Optional<String> toCountSql(Delete delete) {
        if ((delete.getJoins() != null && !delete.getJoins().isEmpty())
                || (delete.getUsingList() != null && !delete.getUsingList().isEmpty())
                || delete.getTable() == null) {
            return Optional.empty();
        }
        return Optional.of(countSelect(delete.getTable(), delete.getWhere()));
    }

    private static String countSelect(net.sf.jsqlparser.schema.Table table,
                                      net.sf.jsqlparser.expression.Expression where) {
        var count = new Function();
        count.setName("COUNT");
        count.setParameters(new ExpressionList<>(new AllColumns()));
        var select = new PlainSelect();
        select.setSelectItems(new ArrayList<>(List.of(new SelectItem<>(count))));
        select.setFromItem(table);
        select.setWhere(where);
        return select.toString();
    }
}
