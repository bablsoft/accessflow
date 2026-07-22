package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.engine.databricks.DatabricksSqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.databricks.DatabricksSqlTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link DatabricksStatement}. Databricks SQL
 * is SQL-shaped, so each matching {@link RowSecurityDirective} is ANDed into the statement's WHERE
 * clause with its comparison values bound as <em>named parameters</em> ({@code :afp_1 … :afp_n},
 * the Statement Execution API's marker syntax) — never string-concatenated. The rewrite is only
 * attempted for provably simple single-table statements: any CTE, subquery, JOIN, comma-join,
 * set operation ({@code UNION}/{@code INTERSECT}/{@code EXCEPT}/{@code MINUS}), {@code MERGE},
 * {@code LATERAL VIEW}, more than one distinct referenced table, or a user statement already
 * containing the literal {@code :afp_} marker text <strong>fails closed</strong>
 * ({@link UnrewritableRowSecurityException}, HTTP 422). INSERT into a policied table is rejected
 * outright (it has no WHERE clause), mirroring the other SQL-shaped engines; DDL is unaffected. An
 * empty value list on a multi-value operator is the fail-closed deny-all signal: the executor
 * returns nothing without any HTTP call.
 */
class DatabricksRowSecurityApplier {

    /** Depth-0 keywords a missing WHERE must be inserted before (Spark SQL clause order). */
    private static final Set<String> WHERE_TAIL = Set.of(
            "GROUP", "HAVING", "QUALIFY", "ORDER", "SORT", "CLUSTER", "DISTRIBUTE", "LIMIT",
            "OFFSET");

    private static final String PARAMETER_PREFIX = ":afp_";

    /**
     * @param statement        the rewritten SQL (unchanged when no policy matches or on deny-all)
     * @param parameters       the named parameters bound to the appended {@code :afp_n} markers,
     *                         in order of appearance
     * @param appliedPolicyIds the policy ids that matched (for audit)
     * @param denyAll          true when a matching policy resolves to "no permitted rows"; the
     *                         executor returns an empty result without executing the statement
     */
    record Applied(String statement, SequencedMap<String, Object> parameters,
                   Set<UUID> appliedPolicyIds, boolean denyAll) {
    }

    private final EngineMessages messages;

    DatabricksRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(DatabricksStatement statement, List<RowSecurityDirective> directives) {
        var matching = matchingDirectives(statement, directives);
        if (matching.isEmpty() || statement.kind() == DatabricksStatementKind.DDL) {
            // DDL has no row semantics — it is governed by the DDL permission + approval path.
            return new Applied(statement.sql(), new LinkedHashMap<>(), Set.of(), false);
        }
        var table = policiedTable(statement, matching);
        if (statement.kind() == DatabricksStatementKind.INSERT) {
            throw new UnrewritableRowSecurityException(messages.get(
                    "error.row_security_databricks_insert_unsupported", table));
        }
        requireRewritable(statement, table);
        var parameters = new LinkedHashMap<String, Object>();
        var policyIds = new LinkedHashSet<UUID>();
        var fragments = new ArrayList<String>(matching.size());
        boolean denyAll = false;
        for (var directive : matching) {
            policyIds.add(directive.policyId());
            if (directive.operator() == RowSecurityOperator.IS_NULL) {
                // Unary — handled before the empty-values deny-all guard (no parameter bound).
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
            // circuits to an empty result rather than issue any HTTP call.
            return new Applied(statement.sql(), new LinkedHashMap<>(), Set.copyOf(policyIds), true);
        }
        var predicate = "(" + String.join(" AND ", fragments) + ")";
        return new Applied(splice(statement, predicate), parameters, Set.copyOf(policyIds), false);
    }

    // ---- rewritability guards -------------------------------------------------------------------

    private void requireRewritable(DatabricksStatement statement, String table) {
        if (statement.kind() == DatabricksStatementKind.MERGE
                || statement.hasCte()
                || statement.tables().size() > 1
                || statement.sql().contains(PARAMETER_PREFIX)
                || hasUnrewritableShape(statement.tokens())) {
            throw new UnrewritableRowSecurityException(messages.get(
                    "error.row_security_databricks_unrewritable", table));
        }
    }

    private static boolean hasUnrewritableShape(List<Token> tokens) {
        for (var token : tokens) {
            if (token.kind() != Kind.WORD) {
                continue;
            }
            // Any nested SELECT is a subquery (scalar, IN (...), EXISTS (...), derived table).
            if (token.isWord("SELECT") && token.depth() > 0) {
                return true;
            }
            if (token.depth() == 0 && (token.isWord("JOIN") || token.isWord("LATERAL")
                    || token.isWord("UNION") || token.isWord("INTERSECT")
                    || token.isWord("EXCEPT") || token.isWord("MINUS"))) {
                return true;
            }
        }
        return hasCommaJoin(tokens);
    }

    /** A depth-0 comma between FROM and the next clause keyword is an implicit join. */
    private static boolean hasCommaJoin(List<Token> tokens) {
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
                    && (token.isWord("WHERE") || WHERE_TAIL.contains(token.value()))) {
                return false;
            }
            if (token.isSymbol(",")) {
                return true;
            }
        }
        return false;
    }

    // ---- predicate building -----------------------------------------------------------------------

    private static String toFragment(RowSecurityDirective directive,
                                     SequencedMap<String, Object> parameters) {
        var column = escapeIdent(directive.columnName());
        return switch (directive.operator()) {
            case IS_NULL -> column + " IS NULL"; // unreachable (handled in apply loop)
            case EQUALS -> bind(column, "=", directive.values().get(0), parameters);
            case NOT_EQUALS -> bind(column, "<>", directive.values().get(0), parameters);
            case LESS_THAN -> bind(column, "<", directive.values().get(0), parameters);
            case LESS_THAN_OR_EQUAL -> bind(column, "<=", directive.values().get(0), parameters);
            case GREATER_THAN -> bind(column, ">", directive.values().get(0), parameters);
            case GREATER_THAN_OR_EQUAL -> bind(column, ">=", directive.values().get(0), parameters);
            case IN -> inFragment(column, directive.values(), parameters, false);
            case NOT_IN -> inFragment(column, directive.values(), parameters, true);
        };
    }

    private static String bind(String column, String operator, Object value,
                               SequencedMap<String, Object> parameters) {
        return column + " " + operator + " " + nextMarker(value, parameters);
    }

    private static String inFragment(String column, List<Object> values,
                                     SequencedMap<String, Object> parameters, boolean negate) {
        var markers = new ArrayList<String>(values.size());
        for (var value : values) {
            markers.add(nextMarker(value, parameters));
        }
        var in = column + " IN (" + String.join(", ", markers) + ")";
        return negate ? column + " NOT IN (" + String.join(", ", markers) + ")" : in;
    }

    private static String nextMarker(Object value, SequencedMap<String, Object> parameters) {
        var name = "afp_" + (parameters.size() + 1);
        parameters.put(name, value);
        return ":" + name;
    }

    private static String escapeIdent(String name) {
        return "`" + name.replace("`", "") + "`";
    }

    // ---- WHERE splice -------------------------------------------------------------------------------

    private static String splice(DatabricksStatement statement, String predicate) {
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
            if (token.depth() == 0 && token.kind() == Kind.WORD
                    && WHERE_TAIL.contains(token.value())) {
                return token.start();
            }
        }
        return tokens.get(tokens.size() - 1).end();
    }

    // ---- directive matching -----------------------------------------------------------------------

    private static List<RowSecurityDirective> matchingDirectives(DatabricksStatement statement,
                                                                 List<RowSecurityDirective> directives) {
        var matching = new ArrayList<RowSecurityDirective>();
        if (directives == null || directives.isEmpty()) {
            return matching;
        }
        var statementTables = new LinkedHashSet<String>();
        for (var table : statement.tables()) {
            statementTables.add(lastSegment(table));
        }
        for (var directive : directives) {
            if (statementTables.contains(lastSegment(directive.tableRef()))) {
                matching.add(directive);
            }
        }
        return matching;
    }

    /** The statement-side table the matched directives police (for error messages). */
    private static String policiedTable(DatabricksStatement statement,
                                        List<RowSecurityDirective> matching) {
        var policied = lastSegment(matching.get(0).tableRef());
        for (var table : statement.tables()) {
            if (lastSegment(table).equals(policied)) {
                return table;
            }
        }
        return policied;
    }

    private static String lastSegment(String ref) {
        if (ref == null) {
            return "";
        }
        var normalized = ref.toLowerCase(Locale.ROOT).strip();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }
}
