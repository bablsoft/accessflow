package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.engine.bigquery.BigQuerySqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.bigquery.BigQuerySqlTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link BigQueryStatement}. GoogleSQL is
 * SQL-shaped, so each matching {@link RowSecurityDirective} is ANDed into the statement's WHERE
 * clause with its comparison values bound as <em>positional {@code ?} parameters</em> (in source
 * order) — never string-concatenated. The rewrite is only provably correct for simple
 * single-table statements, so it <strong>fails closed</strong>
 * ({@code error.row_security_bigquery_unrewritable}) on shapes it cannot rewrite: a WITH
 * prologue, a subquery anywhere, JOINs, comma-joins in FROM, set operations
 * (UNION/INTERSECT/EXCEPT), MERGE, and more than one distinct referenced table (a QUALIFY with a
 * subquery is covered by the subquery rule). INSERT into a policied table is rejected outright
 * ({@code error.row_security_bigquery_insert_unsupported} — it has no WHERE clause), mirroring
 * the DynamoDB / Cassandra engines; DDL is unaffected. An empty value list on a directive is the
 * fail-closed deny-all signal: the executor returns nothing without touching BigQuery.
 */
class BigQueryRowSecurityApplier {

    /** Keywords that can follow the WHERE clause at depth 0 (= the splice boundary). */
    private static final Set<String> WHERE_TAIL = Set.of(
            "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET");

    /**
     * @param statement        the rewritten GoogleSQL (unchanged when no policy matches or on
     *                         deny-all)
     * @param parameters       the values bound to the appended {@code ?} placeholders, in order
     * @param appliedPolicyIds the policy ids that matched (for audit)
     * @param denyAll          true when a matching policy resolves to "no permitted rows"; the
     *                         executor returns an empty result without executing the statement
     */
    record Applied(String statement, List<Object> parameters, Set<UUID> appliedPolicyIds,
                   boolean denyAll) {
    }

    private final EngineMessages messages;

    BigQueryRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(BigQueryStatement statement, List<RowSecurityDirective> directives) {
        if (statement.kind() == BigQueryStatementKind.DDL) {
            return new Applied(statement.sql(), List.of(), Set.of(), false);
        }
        var matching = matchingDirectives(statement, directives);
        if (matching.isEmpty()) {
            return new Applied(statement.sql(), List.of(), Set.of(), false);
        }
        if (statement.kind() == BigQueryStatementKind.INSERT) {
            throw new UnrewritableRowSecurityException(messages.get(
                    "error.row_security_bigquery_insert_unsupported", targetName(statement)));
        }
        requireRewritable(statement);
        var parameters = new ArrayList<Object>();
        var policyIds = new LinkedHashSet<UUID>();
        var fragments = new ArrayList<String>(matching.size());
        boolean denyAll = false;
        for (var directive : matching) {
            policyIds.add(directive.policyId());
            if (directive.operator() == RowSecurityOperator.IS_NULL) {
                // Soft-delete read filter (AF-499): unary, no parameter bound.
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

    // ---- rewritability ------------------------------------------------------------------------

    /** Fail closed on every statement shape the WHERE-splice cannot provably cover. */
    private void requireRewritable(BigQueryStatement statement) {
        if (statement.kind() == BigQueryStatementKind.MERGE
                || statement.hasCte()
                || statement.hasCommaJoin()
                || statement.tables().size() > 1
                || hasSubquery(statement.tokens())
                || hasJoinOrSetOperation(statement.tokens())) {
            throw new UnrewritableRowSecurityException(messages.get(
                    "error.row_security_bigquery_unrewritable", targetName(statement)));
        }
    }

    /** A SELECT below the top level is a subquery (scalar, EXISTS, IN, QUALIFY, ARRAY, …). */
    private static boolean hasSubquery(List<Token> tokens) {
        return tokens.stream().anyMatch(t -> t.depth() > 0 && t.isWord("SELECT"));
    }

    private static boolean hasJoinOrSetOperation(List<Token> tokens) {
        for (var token : tokens) {
            if (token.kind() != Kind.WORD) {
                continue;
            }
            if (token.isWord("JOIN")) {
                return true;
            }
            if (token.depth() == 0 && (token.isWord("UNION") || token.isWord("INTERSECT")
                    || token.isWord("EXCEPT"))) {
                return true;
            }
        }
        return false;
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
            case IN -> inFragment(column, directive.values(), parameters, false);
            case NOT_IN -> inFragment(column, directive.values(), parameters, true);
        };
    }

    private static String bind(String column, String operator, Object value, List<Object> parameters) {
        parameters.add(value);
        return column + " " + operator + " ?";
    }

    private static String inFragment(String column, List<Object> values, List<Object> parameters,
                                     boolean negate) {
        var placeholders = new ArrayList<String>(values.size());
        for (var value : values) {
            parameters.add(value);
            placeholders.add("?");
        }
        var in = column + " IN (" + String.join(", ", placeholders) + ")";
        return negate ? "NOT (" + in + ")" : in;
    }

    /** Backtick-quote each dot segment of the column path; embedded backticks are stripped. */
    private static String escapeIdent(String name) {
        var segments = name.replace("`", "").split("\\.");
        var out = new StringBuilder();
        for (var segment : segments) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append('`').append(segment).append('`');
        }
        return out.toString();
    }

    // ---- WHERE splice ---------------------------------------------------------------------------

    private static String splice(BigQueryStatement statement, String predicate) {
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

    private static List<RowSecurityDirective> matchingDirectives(BigQueryStatement statement,
                                                                 List<RowSecurityDirective> directives) {
        var matching = new ArrayList<RowSecurityDirective>();
        if (directives == null || directives.isEmpty()) {
            return matching;
        }
        var tableNames = new LinkedHashSet<String>();
        for (var table : statement.tables()) {
            tableNames.add(lastSegment(table));
        }
        if (statement.target() != null) {
            tableNames.add(statement.target().lastSegment());
        }
        for (var directive : directives) {
            if (tableNames.contains(lastSegment(directive.tableRef()))) {
                matching.add(directive);
            }
        }
        return matching;
    }

    private static String lastSegment(String ref) {
        if (ref == null) {
            return "";
        }
        var normalized = ref.toLowerCase(Locale.ROOT).strip();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private static String targetName(BigQueryStatement statement) {
        return statement.target() == null ? "" : statement.target().normalized();
    }
}
