package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.engine.snowflake.SnowflakeSqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.snowflake.SnowflakeSqlTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link SnowflakeStatement}. Snowflake SQL
 * is SQL-shaped, so each matching {@link RowSecurityDirective} is ANDed into the statement's WHERE
 * clause with its comparison values bound as <em>positional {@code ?} parameters</em> (in source
 * order) — never string-concatenated. Only provably filterable shapes are rewritten — a simple
 * single-table SELECT / UPDATE / DELETE. When a policied table is referenced from a shape the
 * splice cannot reach (a CTE, any subquery, any JOIN or comma-join table list, a set operation, a
 * MERGE, or a statement touching more than one table), the statement is rejected with
 * {@link UnrewritableRowSecurityException} (HTTP 422) rather than run unfiltered — plain
 * {@code QUALIFY} clauses are fine (the splice lands before them), but a subquery inside one trips
 * the generic subquery check. An empty value list on a non-unary operator is the fail-closed
 * deny-all signal: the executor returns nothing without touching Snowflake. INSERT into a policied
 * table is rejected outright (it has no WHERE clause), mirroring the other engines; DDL (incl.
 * TRUNCATE) is unaffected, at parity with the SQL path.
 */
class SnowflakeRowSecurityApplier {

    /** Keywords that can follow the WHERE clause at depth 0 (= the splice boundary). */
    private static final Set<String> WHERE_TAIL = Set.of(
            "GROUP", "HAVING", "QUALIFY", "ORDER", "LIMIT", "OFFSET", "FETCH");
    private static final Set<String> SET_OPERATORS = Set.of(
            "UNION", "INTERSECT", "EXCEPT", "MINUS");

    /**
     * @param statement        the rewritten SQL (unchanged when no policy matches or on deny-all)
     * @param parameters       the values bound to the appended {@code ?} placeholders, in order
     * @param appliedPolicyIds the policy ids that matched (for audit)
     * @param denyAll          true when a matching policy resolves to "no permitted rows"; the
     *                         executor returns an empty result without executing the statement
     */
    record Applied(String statement, List<Object> parameters, Set<UUID> appliedPolicyIds,
                   boolean denyAll) {
    }

    private final EngineMessages messages;

    SnowflakeRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(SnowflakeStatement statement, List<RowSecurityDirective> directives) {
        var matching = matchingDirectives(statement, directives);
        if (matching.isEmpty()) {
            return new Applied(statement.sql(), List.of(), Set.of(), false);
        }
        if (statement.kind() == SnowflakeStatementKind.DDL) {
            // No rows are read through a filterable clause; parity with the SQL engine.
            return new Applied(statement.sql(), List.of(), Set.of(), false);
        }
        if (statement.kind() == SnowflakeStatementKind.INSERT) {
            if (targetMatches(statement, matching)) {
                throw new UnrewritableRowSecurityException(messages.get(
                        "error.row_security_snowflake_insert_unsupported", targetName(statement)));
            }
            // INSERT … SELECT reading a policied table would leak its rows.
            throw unrewritable(statement, matching);
        }
        if (statement.kind() == SnowflakeStatementKind.MERGE
                || statement.hasCte()
                || statement.tables().size() > 1
                || statement.target() == null
                || hasSubquery(statement.tokens())
                || hasJoin(statement.tokens())
                || hasSetOperation(statement.tokens())
                || hasCommaInFromList(statement.tokens())) {
            throw unrewritable(statement, matching);
        }
        var parameters = new ArrayList<Object>();
        var policyIds = new LinkedHashSet<UUID>();
        var fragments = new ArrayList<String>(matching.size());
        boolean denyAll = false;
        for (var directive : matching) {
            policyIds.add(directive.policyId());
            if (directive.operator() == RowSecurityOperator.IS_NULL) {
                // Soft-delete read filter (AF-499): unary, no bound parameter.
                fragments.add(escapeIdent(directive.columnName()) + " IS NULL");
                continue;
            }
            if (directive.values().isEmpty()) {
                denyAll = true;
                continue;
            }
            fragments.add(toFragment(directive, parameters));
        }
        if (denyAll) {
            // Fail closed: a deny-all predicate makes the AND unsatisfiable; the executor short
            // circuits to an empty result rather than run the statement at all.
            return new Applied(statement.sql(), List.of(), Set.copyOf(policyIds), true);
        }
        var predicate = "(" + String.join(" AND ", fragments) + ")";
        return new Applied(splice(statement, predicate), List.copyOf(parameters),
                Set.copyOf(policyIds), false);
    }

    // ---- predicate building -------------------------------------------------------------------

    private static String toFragment(RowSecurityDirective directive, List<Object> parameters) {
        var column = escapeIdent(directive.columnName());
        return switch (directive.operator()) {
            case IS_NULL -> column + " IS NULL"; // unreachable (handled in apply loop)
            case EQUALS -> bind(column, "=", directive.values().get(0), parameters);
            case NOT_EQUALS -> bind(column, "<>", directive.values().get(0), parameters);
            case LESS_THAN -> bind(column, "<", directive.values().get(0), parameters);
            case LESS_THAN_OR_EQUAL -> bind(column, "<=", directive.values().get(0), parameters);
            case GREATER_THAN -> bind(column, ">", directive.values().get(0), parameters);
            case GREATER_THAN_OR_EQUAL -> bind(column, ">=", directive.values().get(0), parameters);
            case IN -> inFragment(column, "IN", directive.values(), parameters);
            case NOT_IN -> inFragment(column, "NOT IN", directive.values(), parameters);
        };
    }

    private static String bind(String column, String operator, Object value,
                               List<Object> parameters) {
        parameters.add(value);
        return column + " " + operator + " ?";
    }

    private static String inFragment(String column, String operator, List<Object> values,
                                     List<Object> parameters) {
        var placeholders = new ArrayList<String>(values.size());
        for (var value : values) {
            parameters.add(value);
            placeholders.add("?");
        }
        return column + " " + operator + " (" + String.join(", ", placeholders) + ")";
    }

    private static String escapeIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    // ---- structural fail-closed checks ----------------------------------------------------------

    /** Any paren group opening directly onto a SELECT / WITH — a subquery in any position. */
    private static boolean hasSubquery(List<Token> tokens) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).isSymbol("(")
                    && (tokens.get(i + 1).isWord("SELECT") || tokens.get(i + 1).isWord("WITH"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJoin(List<Token> tokens) {
        return tokens.stream().anyMatch(t -> t.isWord("JOIN"));
    }

    private static boolean hasSetOperation(List<Token> tokens) {
        return tokens.stream().anyMatch(t -> t.depth() == 0 && t.kind() == Kind.WORD
                && SET_OPERATORS.contains(t.value()));
    }

    /** A depth-0 comma between FROM and the next clause boundary — an implicit comma-join. */
    private static boolean hasCommaInFromList(List<Token> tokens) {
        int fromIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).depth() == 0 && tokens.get(i).isWord("FROM")) {
                fromIndex = i;
                break;
            }
        }
        if (fromIndex < 0) {
            return false;
        }
        for (int i = fromIndex + 1; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() != 0) {
                continue;
            }
            if (token.kind() == Kind.WORD
                    && (token.value().equals("WHERE") || WHERE_TAIL.contains(token.value()))) {
                return false;
            }
            if (token.isSymbol(",")) {
                return true;
            }
        }
        return false;
    }

    // ---- WHERE splice ---------------------------------------------------------------------------

    private static String splice(SnowflakeStatement statement, String predicate) {
        var sql = statement.sql();
        var tokens = statement.tokens();
        int whereIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.isWord("WHERE")) {
                whereIndex = i;
                break;
            }
        }
        if (whereIndex >= 0) {
            int clauseEnd = clauseEndOffset(tokens, whereIndex + 1);
            var existing = sql.substring(tokens.get(whereIndex).end(), clauseEnd).strip();
            return (sql.substring(0, tokens.get(whereIndex).start())
                    + "WHERE (" + existing + ") AND " + predicate
                    + " " + sql.substring(clauseEnd)).stripTrailing();
        }
        int insertAt = clauseEndOffset(tokens, 0);
        return (sql.substring(0, insertAt).stripTrailing() + " WHERE " + predicate + " "
                + sql.substring(insertAt)).stripTrailing();
    }

    /** Offset of the first depth-0 tail keyword at/after {@code from}, else the statement end. */
    private static int clauseEndOffset(List<Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.kind() == Kind.WORD && WHERE_TAIL.contains(token.value())) {
                return token.start();
            }
        }
        return tokens.get(tokens.size() - 1).end();
    }

    // ---- directive matching ---------------------------------------------------------------------

    /** Directives whose (last-segment, lowercased) table matches any table the statement touches. */
    private static List<RowSecurityDirective> matchingDirectives(SnowflakeStatement statement,
                                                                 List<RowSecurityDirective> directives) {
        var matching = new ArrayList<RowSecurityDirective>();
        if (directives == null || statement.tables().isEmpty()) {
            return matching;
        }
        var tables = new LinkedHashSet<String>();
        for (var table : statement.tables()) {
            tables.add(lastSegment(table));
        }
        for (var directive : directives) {
            if (tables.contains(lastSegment(directive.tableRef()))) {
                matching.add(directive);
            }
        }
        return matching;
    }

    private static boolean targetMatches(SnowflakeStatement statement,
                                         List<RowSecurityDirective> matching) {
        if (statement.target() == null) {
            return false;
        }
        var target = statement.target().lastSegment();
        return matching.stream().anyMatch(d -> lastSegment(d.tableRef()).equals(target));
    }

    private static String lastSegment(String ref) {
        if (ref == null) {
            return "";
        }
        var normalized = ref.toLowerCase(Locale.ROOT).strip();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private static String targetName(SnowflakeStatement statement) {
        return statement.target() == null ? "" : statement.target().normalized();
    }

    private UnrewritableRowSecurityException unrewritable(SnowflakeStatement statement,
                                                          List<RowSecurityDirective> matching) {
        var table = statement.target() != null
                ? statement.target().normalized()
                : matching.get(0).tableRef();
        return new UnrewritableRowSecurityException(messages.get(
                "error.row_security_snowflake_unrewritable", table));
    }
}
