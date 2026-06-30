package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.engine.dynamodb.PartiQlTokenizer.Kind;
import com.bablsoft.accessflow.engine.dynamodb.PartiQlTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link PartiQlStatement}. PartiQL is
 * SQL-shaped, so each matching {@link RowSecurityDirective} is ANDed into the statement's WHERE
 * clause with its comparison values bound as <em>positional {@code ?} parameters</em> (in source
 * order) — never string-concatenated. Unlike CQL, DynamoDB PartiQL can filter on any attribute (a
 * non-key predicate becomes a server-side Scan filter), so the applier is not key-restricted; it
 * supports {@code =, <>, <, <=, >, >=, IN, NOT IN}. An empty value list is the fail-closed deny-all
 * signal: the executor returns nothing without touching DynamoDB. INSERT into a policied table is
 * rejected outright (it has no WHERE clause), mirroring the MongoDB / Cassandra engines; DDL is
 * unaffected.
 */
class DynamoDbRowSecurityApplier {

    /** Keywords that can follow the WHERE clause at depth 0 (= the splice boundary). */
    private static final Set<String> WHERE_TAIL = Set.of("ORDER", "RETURNING");

    /**
     * @param statement     the rewritten PartiQL (unchanged when no policy matches or on deny-all)
     * @param parameters    the values bound to the appended {@code ?} placeholders, in order
     * @param appliedPolicyIds the policy ids that matched (for audit)
     * @param denyAll       true when a matching policy resolves to "no permitted rows"; the executor
     *                      returns an empty result without executing the statement
     */
    record Applied(String statement, List<Object> parameters, Set<UUID> appliedPolicyIds,
                   boolean denyAll) {
    }

    private final EngineMessages messages;

    DynamoDbRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(PartiQlStatement statement, List<RowSecurityDirective> directives) {
        var matching = matchingDirectives(statement, directives);
        if (matching.isEmpty()) {
            return new Applied(statement.sql(), List.of(), Set.of(), false);
        }
        if (statement.kind() == PartiQlStatementKind.INSERT) {
            throw new UnrewritableRowSecurityException(messages.get(
                    "error.row_security_dynamodb_insert_unsupported", targetName(statement)));
        }
        var parameters = new ArrayList<Object>();
        var policyIds = new LinkedHashSet<UUID>();
        var fragments = new ArrayList<String>(matching.size());
        boolean denyAll = false;
        for (var directive : matching) {
            policyIds.add(directive.policyId());
            if (directive.operator() == RowSecurityOperator.IS_NULL) {
                // Soft-delete read filter (AF-499): PartiQL IS MISSING matches items lacking the attr.
                fragments.add(escapeIdent(directive.columnName()) + " IS MISSING");
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
            // circuits to an empty result rather than emit a constant predicate PartiQL may reject.
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
            case IS_NULL -> column + " IS MISSING"; // unreachable (handled in apply loop)
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
        var in = column + " IN [" + String.join(", ", placeholders) + "]";
        return negate ? "NOT (" + in + ")" : in;
    }

    private static String escapeIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    // ---- WHERE splice ---------------------------------------------------------------------------

    private static String splice(PartiQlStatement statement, String predicate) {
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

    private static List<RowSecurityDirective> matchingDirectives(PartiQlStatement statement,
                                                                 List<RowSecurityDirective> directives) {
        var matching = new ArrayList<RowSecurityDirective>();
        if (directives == null || statement.target() == null) {
            return matching;
        }
        var table = statement.target().normalized().toLowerCase(Locale.ROOT);
        for (var directive : directives) {
            if (lastSegment(directive.tableRef()).equals(table)) {
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

    private static String targetName(PartiQlStatement statement) {
        return statement.target() == null ? "" : statement.target().normalized();
    }
}
