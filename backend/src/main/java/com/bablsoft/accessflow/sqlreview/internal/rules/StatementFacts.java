package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.core.api.QueryType;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Everything a custom rule's condition tree can ask about one statement (#1009), derived once per
 * {@link SqlRuleContext} (see {@link SqlRuleContext#facts()}) and shared by every custom rule. Each
 * fact reuses the helper the matching built-in rule uses — {@link WhereClauses},
 * {@link CartesianJoins}, {@link LeadingWildcards}, {@link RowLimits}, {@link TableNames},
 * {@link StatementKinds} — so a custom leaf and its built-in counterpart cannot disagree.
 *
 * <p>{@link #normalizedText()} is the deparsed statement with whitespace runs collapsed to one
 * space; deparsing drops comments, so a pattern cannot be defeated by hiding text in one.
 */
public final class StatementFacts {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final QueryType queryType;
    private final Set<String> tables;
    private final Set<String> columns;
    private final Set<String> functions;
    private final boolean hasWhere;
    private final boolean hasLimit;
    private final boolean hasOrderBy;
    private final boolean whereAlwaysTrue;
    private final boolean joinWithoutCondition;
    private final boolean leadingWildcardLike;
    private final boolean transactional;
    private final String normalizedText;

    private StatementFacts(SqlRuleContext context) {
        var statement = context.statement();
        var walker = StatementWalker.walk(statement);
        this.queryType = classify(statement);
        this.tables = TableNames.referencedTables(statement);
        this.columns = columnsOf(walker.columns());
        this.functions = functionsOf(walker.functions());
        this.hasWhere = WhereClauses.hasWhere(statement);
        this.hasLimit = hasRowLimit(statement);
        this.hasOrderBy = hasOrderBy(statement);
        this.whereAlwaysTrue = !WhereClauses.alwaysTrue(statement).isEmpty();
        this.joinWithoutCondition = !CartesianJoins.find(walker).isEmpty();
        this.leadingWildcardLike = !LeadingWildcards.find(walker).isEmpty();
        this.transactional = context.transactional();
        this.normalizedText = WHITESPACE.matcher(statement.toString()).replaceAll(" ").trim();
    }

    static StatementFacts of(SqlRuleContext context) {
        return new StatementFacts(context);
    }

    /** {@code SELECT} / {@code INSERT} / {@code UPDATE} / {@code DELETE} / {@code DDL} / {@code OTHER}, as the proxy classifies it. */
    public QueryType queryType() {
        return queryType;
    }

    /** Referenced tables, normalised {@code schema.table} or bare, sorted. */
    public Set<String> tables() {
        return tables;
    }

    /** Referenced columns as written — {@code o.email} or {@code email} — normalised, sorted. */
    public Set<String> columns() {
        return columns;
    }

    /** Called functions by unqualified, normalised name, sorted. */
    public Set<String> functions() {
        return functions;
    }

    public boolean hasWhere() {
        return hasWhere;
    }

    public boolean hasLimit() {
        return hasLimit;
    }

    public boolean hasOrderBy() {
        return hasOrderBy;
    }

    public boolean whereAlwaysTrue() {
        return whereAlwaysTrue;
    }

    public boolean joinWithoutCondition() {
        return joinWithoutCondition;
    }

    public boolean leadingWildcardLike() {
        return leadingWildcardLike;
    }

    public boolean transactional() {
        return transactional;
    }

    public String normalizedText() {
        return normalizedText;
    }

    private static QueryType classify(Statement statement) {
        return switch (statement) {
            case Select ignored -> QueryType.SELECT;
            case Insert ignored -> QueryType.INSERT;
            case Update ignored -> QueryType.UPDATE;
            case Delete ignored -> QueryType.DELETE;
            default -> StatementKinds.isDdl(statement) ? QueryType.DDL : QueryType.OTHER;
        };
    }

    private static boolean hasRowLimit(Statement statement) {
        return switch (statement) {
            case Select select -> RowLimits.hasRowLimit(select);
            case Update update -> update.getLimit() != null;
            case Delete delete -> delete.getLimit() != null;
            default -> false;
        };
    }

    private static boolean hasOrderBy(Statement statement) {
        return switch (statement) {
            case Select select -> RowLimits.hasOrderBy(select);
            case Update update -> notEmpty(update.getOrderByElements());
            case Delete delete -> notEmpty(delete.getOrderByElements());
            default -> false;
        };
    }

    private static boolean notEmpty(List<OrderByElement> elements) {
        return elements != null && !elements.isEmpty();
    }

    private static Set<String> columnsOf(List<Column> columns) {
        var out = new TreeSet<String>();
        for (Column column : columns) {
            var name = TableNames.normalize(column.getFullyQualifiedName());
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return Collections.unmodifiableSortedSet(out);
    }

    private static Set<String> functionsOf(List<Function> functions) {
        var out = new TreeSet<String>();
        for (Function function : functions) {
            var name = DisallowedFunctionRule.bareName(function);
            if (!name.isEmpty()) {
                out.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSortedSet(out);
    }
}
