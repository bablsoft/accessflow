package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.SoftDeleteDirective;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Injects resolved {@link RowSecurityDirective}s into a parsed SQL statement so a query only
 * returns (SELECT) or affects (UPDATE/DELETE) the rows the submitter is authorised for.
 *
 * <p>For SELECT, every top-level FROM/JOIN reference to a policied table is replaced with a
 * security-barrier derived table {@code (SELECT * FROM t WHERE <predicate>) t}. For UPDATE/DELETE
 * the predicate is ANDed (qualified to the target) into the WHERE clause. Comparison values are
 * always bound as JDBC parameters — never string-concatenated. Statement shapes the rewriter
 * cannot provably filter (UNION, CTE, sub-selects onto policied tables, INSERT…SELECT,
 * UPDATE…FROM/DELETE…USING joins) are rejected with {@link UnrewritableRowSecurityException}
 * (HTTP 422) rather than run unfiltered.
 */
@Component
@RequiredArgsConstructor
class RowSecurityRewriter {

    private final MessageSource messageSource;

    /** Result of a rewrite: the (possibly unchanged) SQL, the ordered JDBC bind values, and the
     *  ids of the policies actually applied (for audit). */
    record RewriteResult(String sql, List<Object> binds, Set<UUID> appliedPolicyIds) {
        static RewriteResult noop(String sql) {
            return new RewriteResult(sql, List.of(), Set.of());
        }
    }

    RewriteResult rewrite(String sql, List<RowSecurityDirective> directives) {
        return rewrite(sql, directives, List.of());
    }

    RewriteResult rewrite(String sql, List<RowSecurityDirective> directives,
                          List<SoftDeleteDirective> softDeletes) {
        var rowSecurity = directives == null ? List.<RowSecurityDirective>of() : directives;
        var soft = softDeletes == null ? List.<SoftDeleteDirective>of() : softDeletes;
        if (rowSecurity.isEmpty() && soft.isEmpty()) {
            return RewriteResult.noop(sql);
        }
        var statement = parse(sql);
        var binds = new ArrayList<Object>();
        var applied = new LinkedHashSet<UUID>();
        // Soft-delete (AF-499): a DELETE against a soft-delete target becomes UPDATE … SET marker =
        // CURRENT_TIMESTAMP. The matching IS_NULL row-security directive (resolved alongside) then
        // scopes the UPDATE to rows not already soft-deleted.
        if (statement instanceof Delete delete) {
            var match = matchingSoftDelete(delete.getTable(), soft);
            if (match != null) {
                statement = toSoftDeleteUpdate(delete, match);
                applied.add(match.policyId());
            }
        }
        switch (statement) {
            case Select select -> rewriteSelect(select, rowSecurity, binds, applied);
            case Update update -> rewriteDml(update.getTable(), update, rowSecurity, binds, applied,
                    update::getWhere, update::setWhere);
            case Delete delete -> rewriteDml(delete.getTable(), delete, rowSecurity, binds, applied,
                    delete::getWhere, delete::setWhere);
            case Insert insert -> rejectInsertOnPolicied(insert, rowSecurity);
            default -> { /* DDL / OTHER: row security does not apply (no rows read/affected). */ }
        }
        if (applied.isEmpty()) {
            return RewriteResult.noop(sql);
        }
        return new RewriteResult(statement.toString(), binds, applied);
    }

    // ---- soft delete ----------------------------------------------------------------------------

    private SoftDeleteDirective matchingSoftDelete(Table target, List<SoftDeleteDirective> softDeletes) {
        if (target == null || softDeletes.isEmpty()) {
            return null;
        }
        var schema = SqlParserServiceImpl.normalizeIdentifier(target.getSchemaName());
        var name = SqlParserServiceImpl.normalizeIdentifier(target.getName());
        for (var sd : softDeletes) {
            if (matchesRef(sd.tableRef(), schema, name)) {
                return sd;
            }
        }
        return null;
    }

    private Update toSoftDeleteUpdate(Delete delete, SoftDeleteDirective directive) {
        // Only a plain DELETE FROM t [WHERE …] is convertible; DELETE … USING / multi-table joins
        // would change semantics when expressed as an UPDATE, so reject them fail-closed.
        if ((delete.getJoins() != null && !delete.getJoins().isEmpty())
                || (delete.getUsingList() != null && !delete.getUsingList().isEmpty())) {
            throw reject("error.row_security_dml_join_unsupported");
        }
        var update = new Update();
        update.setTable(delete.getTable());
        update.addUpdateSet(new Column(directive.markerColumn()), nowExpression());
        if (delete.getWhere() != null) {
            update.setWhere(delete.getWhere());
        }
        return update;
    }

    private Expression nowExpression() {
        try {
            return CCJSqlParserUtil.parseExpression("CURRENT_TIMESTAMP");
        } catch (JSQLParserException ex) {
            // CURRENT_TIMESTAMP is standard SQL and always parses; unreachable in practice.
            throw new IllegalStateException("CURRENT_TIMESTAMP did not parse", ex);
        }
    }

    // ---- SELECT ---------------------------------------------------------------------------------

    private void rewriteSelect(Select select, List<RowSecurityDirective> directives,
                               List<Object> binds, Set<UUID> applied) {
        var policiedReferenced = policiedReferenced(select, directives);
        if (policiedReferenced.isEmpty()) {
            return;
        }
        if (!(select instanceof PlainSelect plain)) {
            throw reject("error.row_security_union_unsupported");
        }
        if (plain.getWithItemsList() != null && !plain.getWithItemsList().isEmpty()) {
            throw reject("error.row_security_cte_unsupported");
        }
        // Every top-level FROM/JOIN item must be a plain Table; a derived table (sub-select) onto a
        // policied table cannot be barrier-wrapped, so reject rather than leak.
        rejectIfDerivedFromItems(plain);
        // Reject when a policied table is reachable only through a sub-select in any expression
        // position (WHERE, HAVING, JOIN ON, select items, GROUP BY, ORDER BY).
        rejectIfPoliciedInExpressions(plain, directives);

        var wrapped = new HashSet<String>();
        if (plain.getFromItem() instanceof Table fromTable) {
            var matching = matching(fromTable, directives);
            if (!matching.isEmpty()) {
                wrapped.add(normalizedFqn(fromTable));
                plain.setFromItem(wrap(fromTable, matching, binds, applied));
            }
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getRightItem() instanceof Table joinTable) {
                    var matching = matching(joinTable, directives);
                    if (!matching.isEmpty()) {
                        wrapped.add(normalizedFqn(joinTable));
                        join.setRightItem(wrap(joinTable, matching, binds, applied));
                    }
                }
            }
        }
        // Backstop: every policied table referenced anywhere must have been wrapped above. A
        // leftover means it appears in a position we did not handle — reject rather than leak.
        var leftover = new HashSet<>(policiedReferenced);
        leftover.removeAll(wrapped);
        if (!leftover.isEmpty()) {
            throw reject("error.row_security_subselect_unsupported");
        }
    }

    private void rejectIfDerivedFromItems(PlainSelect plain) {
        if (plain.getFromItem() != null && !(plain.getFromItem() instanceof Table)) {
            throw reject("error.row_security_subselect_unsupported");
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getRightItem() != null && !(join.getRightItem() instanceof Table)) {
                    throw reject("error.row_security_subselect_unsupported");
                }
            }
        }
    }

    private void rejectIfPoliciedInExpressions(PlainSelect plain,
                                               List<RowSecurityDirective> directives) {
        if (referencesPolicied(plain.getWhere(), directives)
                || referencesPolicied(plain.getHaving(), directives)) {
            throw reject("error.row_security_subselect_unsupported");
        }
        if (plain.getSelectItems() != null) {
            for (SelectItem<?> item : plain.getSelectItems()) {
                if (referencesPolicied(item.getExpression(), directives)) {
                    throw reject("error.row_security_subselect_unsupported");
                }
            }
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        if (referencesPolicied(on, directives)) {
                            throw reject("error.row_security_subselect_unsupported");
                        }
                    }
                }
            }
        }
        if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressionList() != null) {
            for (Object expr : plain.getGroupBy().getGroupByExpressionList()) {
                if (expr instanceof Expression e && referencesPolicied(e, directives)) {
                    throw reject("error.row_security_subselect_unsupported");
                }
            }
        }
        if (plain.getOrderByElements() != null) {
            for (OrderByElement element : plain.getOrderByElements()) {
                if (referencesPolicied(element.getExpression(), directives)) {
                    throw reject("error.row_security_subselect_unsupported");
                }
            }
        }
    }

    private FromItem wrap(Table table, List<RowSecurityDirective> matching, List<Object> binds,
                          Set<UUID> applied) {
        var outerAlias = table.getAlias() != null ? table.getAlias() : new Alias(table.getName());
        table.setAlias(null);
        var inner = new PlainSelect();
        inner.setSelectItems(new ArrayList<>(List.of(new SelectItem<>(new AllColumns()))));
        inner.setFromItem(table);
        Expression where = null;
        for (var directive : matching) {
            var predicate = predicate(new Column(directive.columnName()), directive, binds);
            where = where == null ? predicate : new AndExpression(where, predicate);
            applied.add(directive.policyId());
        }
        inner.setWhere(where);
        var barrier = new net.sf.jsqlparser.statement.select.ParenthesedSelect();
        barrier.setSelect(inner);
        barrier.setAlias(outerAlias);
        return barrier;
    }

    // ---- UPDATE / DELETE ------------------------------------------------------------------------

    private void rewriteDml(Table target, Statement statement, List<RowSecurityDirective> directives,
                            List<Object> binds, Set<UUID> applied,
                            java.util.function.Supplier<Expression> getWhere,
                            java.util.function.Consumer<Expression> setWhere) {
        var policiedReferenced = policiedReferenced(statement, directives);
        if (policiedReferenced.isEmpty()) {
            return;
        }
        if (target == null) {
            throw reject("error.row_security_dml_join_unsupported");
        }
        var matching = matching(target, directives);
        var qualifier = target.getAlias() != null ? target.getAlias().getName() : target.getName();
        Expression where = getWhere.get();
        for (var directive : matching) {
            var predicate = predicate(new Column(new Table(qualifier), directive.columnName()),
                    directive, binds);
            where = where == null ? predicate : new AndExpression(where, predicate);
            applied.add(directive.policyId());
        }
        if (where != getWhere.get()) {
            setWhere.accept(where);
        }
        // The only policied table we can filter in an UPDATE/DELETE is the mutation target. Any
        // other policied reference (UPDATE…FROM, DELETE…USING, a sub-select) cannot be filtered.
        var leftover = new HashSet<>(policiedReferenced);
        leftover.remove(normalizedFqn(target));
        if (!leftover.isEmpty()) {
            throw reject("error.row_security_dml_join_unsupported");
        }
    }

    // ---- INSERT ---------------------------------------------------------------------------------

    private void rejectInsertOnPolicied(Insert insert, List<RowSecurityDirective> directives) {
        // INSERT is outside row-security enforcement, but an INSERT…SELECT that reads a policied
        // table would leak its rows — reject those.
        if (insert.getSelect() != null && referencesPolicied(insert.getSelect(), directives)) {
            throw reject("error.row_security_insert_select_unsupported");
        }
    }

    // ---- predicate building ---------------------------------------------------------------------

    private Expression predicate(Column column, RowSecurityDirective directive, List<Object> binds) {
        if (directive.operator() == RowSecurityOperator.IS_NULL) {
            return new IsNullExpression(column); // unary; binds nothing (soft-delete read filter)
        }
        var values = directive.values();
        if (values.isEmpty()) {
            return alwaysFalse(); // fail-closed: unresolvable variable / empty list → no rows
        }
        var operator = directive.operator();
        if (operator.isMultiValue()) {
            var list = new ParenthesedExpressionList<JdbcParameter>();
            for (var value : values) {
                list.add(new JdbcParameter());
                binds.add(value);
            }
            var in = new InExpression();
            in.setLeftExpression(column);
            in.setRightExpression(list);
            in.setNot(operator == RowSecurityOperator.NOT_IN);
            return in;
        }
        var parameter = new JdbcParameter();
        binds.add(values.get(0));
        return scalarComparison(operator, column, parameter);
    }

    private static Expression scalarComparison(RowSecurityOperator operator, Column column,
                                               Expression value) {
        return switch (operator) {
            case EQUALS -> new EqualsTo(column, value);
            case NOT_EQUALS -> new NotEqualsTo(column, value);
            case LESS_THAN -> new MinorThan(column, value);
            case LESS_THAN_OR_EQUAL -> new MinorThanEquals(column, value);
            case GREATER_THAN -> new GreaterThan(column, value);
            case GREATER_THAN_OR_EQUAL -> new GreaterThanEquals(column, value);
            case IN, NOT_IN -> throw new IllegalStateException("multi-value operator " + operator);
            case IS_NULL -> throw new IllegalStateException("IS_NULL handled before comparison");
        };
    }

    private static Expression alwaysFalse() {
        return new EqualsTo(new LongValue(1), new LongValue(0));
    }

    // ---- table matching / reference analysis ----------------------------------------------------

    private List<RowSecurityDirective> matching(Table table, List<RowSecurityDirective> directives) {
        var schema = SqlParserServiceImpl.normalizeIdentifier(table.getSchemaName());
        var name = SqlParserServiceImpl.normalizeIdentifier(table.getName());
        var result = new ArrayList<RowSecurityDirective>();
        for (var directive : directives) {
            if (matches(directive, schema, name)) {
                result.add(directive);
            }
        }
        return result;
    }

    private static boolean matches(RowSecurityDirective directive, String schema, String name) {
        return matchesRef(directive.tableRef(), schema, name);
    }

    private static boolean matchesRef(String tableRef, String schema, String name) {
        var ref = SqlParserServiceImpl.normalizeIdentifier(tableRef);
        var dot = ref.lastIndexOf('.');
        var policyName = dot < 0 ? ref : ref.substring(dot + 1);
        var policySchema = dot < 0 ? "" : ref.substring(0, dot);
        if (!policyName.equals(name)) {
            return false;
        }
        if (policySchema.isEmpty() || schema == null || schema.isEmpty()) {
            return true;
        }
        var policySchemaLast = policySchema.lastIndexOf('.');
        var policySchemaSegment = policySchemaLast < 0
                ? policySchema : policySchema.substring(policySchemaLast + 1);
        return policySchemaSegment.equals(schema);
    }

    private boolean isPolicied(String tableName, List<RowSecurityDirective> directives) {
        var norm = SqlParserServiceImpl.normalizeIdentifier(tableName);
        var dot = norm.lastIndexOf('.');
        var name = dot < 0 ? norm : norm.substring(dot + 1);
        var schema = dot < 0 ? "" : norm.substring(0, dot);
        for (var directive : directives) {
            if (matches(directive, schema, name)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedFqn(Table table) {
        return SqlParserServiceImpl.normalizeIdentifier(table.getFullyQualifiedName());
    }

    private Set<String> policiedReferenced(Statement statement,
                                           List<RowSecurityDirective> directives) {
        Set<String> raw;
        try {
            raw = new TablesNamesFinder<>().getTables(statement);
        } catch (RuntimeException ex) {
            throw reject("error.row_security_subselect_unsupported");
        }
        var out = new HashSet<String>();
        if (raw != null) {
            for (var name : raw) {
                if (isPolicied(name, directives)) {
                    out.add(SqlParserServiceImpl.normalizeIdentifier(name));
                }
            }
        }
        return out;
    }

    private boolean referencesPolicied(Expression expression, List<RowSecurityDirective> directives) {
        if (expression == null) {
            return false;
        }
        Set<String> names;
        try {
            names = new TablesNamesFinder<>().getTables(expression);
        } catch (RuntimeException ex) {
            return true; // cannot verify the expression — treat conservatively as policied
        }
        if (names == null) {
            return false;
        }
        for (var name : names) {
            if (isPolicied(name, directives)) {
                return true;
            }
        }
        return false;
    }

    private boolean referencesPolicied(Select select, List<RowSecurityDirective> directives) {
        return !policiedReferenced(select, directives).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException ex) {
            throw new InvalidSqlException(msg("error.sql_parse_failed"), ex);
        }
    }

    private UnrewritableRowSecurityException reject(String key) {
        return new UnrewritableRowSecurityException(msg(key));
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
