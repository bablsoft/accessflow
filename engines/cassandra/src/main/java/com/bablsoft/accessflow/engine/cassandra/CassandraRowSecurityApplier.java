package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.engine.cassandra.CqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.cassandra.CqlTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link CqlStatement}. CQL is SQL-shaped, so
 * each matching {@link RowSecurityDirective} is ANDed into the statement's WHERE clause with its
 * comparison values bound as <em>named parameters</em> ({@code :af_rls_n}) — never
 * string-concatenated. Unlike the SQL/Couchbase engines, CQL cannot filter on an arbitrary column:
 * a predicate on a non-key column would require {@code ALLOW FILTERING} (a full-table scan the
 * proxy must never silently inject), and CQL's WHERE has no {@code !=} / {@code NOT IN}. So a
 * directive is spliced <em>only</em> when its column is a partition/clustering key of the target
 * table <em>and</em> its operator is one of {@code =, IN, <, <=, >, >=}; anything else (non-key
 * column, unsupported operator, empty/deny-all value list) fails closed with
 * {@link UnrewritableRowSecurityException} (HTTP 422) rather than running unfiltered. INSERT into a
 * policied table is rejected outright (Cassandra INSERT is an upsert), mirroring the MongoDB and
 * Couchbase engines. DDL is unaffected.
 */
class CassandraRowSecurityApplier {

    /** Keywords that can follow the WHERE clause at depth 0 (= the splice boundary). */
    private static final Set<String> WHERE_TAIL = Set.of(
            "GROUP", "ORDER", "PER", "LIMIT", "ALLOW", "IF");

    static final String PARAM_PREFIX = "af_rls_";

    record Applied(String cql, Map<String, Object> parameters, Set<UUID> appliedPolicyIds) {
    }

    private final EngineMessages messages;

    CassandraRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    /**
     * @param keyColumns the target table's partition + clustering key column names (canonical
     *                   internal form), resolved by the executor from the live {@code CqlSession}
     *                   metadata; the only columns a predicate may safely target.
     */
    Applied apply(CqlStatement statement, List<RowSecurityDirective> directives,
                  Set<String> keyColumns) {
        var matching = matchingDirectives(statement, directives);
        if (matching.isEmpty()) {
            return new Applied(statement.sql(), Map.of(), Set.of());
        }
        if (statement.kind() == CqlStatementKind.DDL) {
            return new Applied(statement.sql(), Map.of(), Set.of());
        }
        if (statement.kind() == CqlStatementKind.INSERT) {
            throw new UnrewritableRowSecurityException(messages.get(
                    "error.row_security_cassandra_insert_unsupported", targetName(statement)));
        }
        var keyByLower = keysByLowercase(keyColumns);
        var parameters = new LinkedHashMap<String, Object>();
        var policyIds = new LinkedHashSet<UUID>();
        var fragments = new ArrayList<String>(matching.size());
        for (var directive : matching) {
            fragments.add(toFragment(directive, keyByLower, parameters, statement));
            policyIds.add(directive.policyId());
        }
        var predicate = "(" + String.join(" AND ", fragments) + ")";
        return new Applied(splice(statement, predicate), Map.copyOf(parameters),
                Set.copyOf(policyIds));
    }

    // ---- predicate building -------------------------------------------------------------------

    private String toFragment(RowSecurityDirective directive, Map<String, String> keyByLower,
                              Map<String, Object> parameters, CqlStatement statement) {
        var canonical = keyByLower.get(directive.columnName().toLowerCase(Locale.ROOT));
        if (canonical == null || directive.values().isEmpty()
                || directive.operator() == RowSecurityOperator.NOT_EQUALS
                || directive.operator() == RowSecurityOperator.NOT_IN) {
            // Non-key column, deny-all, or an operator CQL cannot express without ALLOW FILTERING.
            throw unrewritable(statement);
        }
        var column = escapeIdent(canonical);
        var paramName = PARAM_PREFIX + parameters.size();
        return switch (directive.operator()) {
            case EQUALS -> bind(column, "=", paramName, directive.values().get(0), parameters);
            case LESS_THAN -> bind(column, "<", paramName, directive.values().get(0), parameters);
            case LESS_THAN_OR_EQUAL ->
                    bind(column, "<=", paramName, directive.values().get(0), parameters);
            case GREATER_THAN -> bind(column, ">", paramName, directive.values().get(0), parameters);
            case GREATER_THAN_OR_EQUAL ->
                    bind(column, ">=", paramName, directive.values().get(0), parameters);
            case IN -> bind(column, "IN", paramName, List.copyOf(directive.values()), parameters);
            default -> throw unrewritable(statement);
        };
    }

    private static String bind(String column, String operator, String paramName, Object value,
                               Map<String, Object> parameters) {
        parameters.put(paramName, value);
        return column + " " + operator + " :" + paramName;
    }

    private static String escapeIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    // ---- WHERE splice ---------------------------------------------------------------------------

    private static String splice(CqlStatement statement, String predicate) {
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
            return sql.substring(0, tokens.get(whereIndex).start())
                    + "WHERE (" + existing + ") AND " + predicate
                    + " " + sql.substring(clauseEnd);
        }
        int insertAt = clauseEndOffset(tokens, 0);
        return sql.substring(0, insertAt).stripTrailing() + " WHERE " + predicate + " "
                + sql.substring(insertAt);
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

    private static List<RowSecurityDirective> matchingDirectives(CqlStatement statement,
                                                                 List<RowSecurityDirective> directives) {
        var matching = new ArrayList<RowSecurityDirective>();
        if (directives == null || statement.target() == null) {
            return matching;
        }
        var table = statement.target().table().toLowerCase(Locale.ROOT);
        for (var directive : directives) {
            if (lastSegment(directive.tableRef()).equals(table)) {
                matching.add(directive);
            }
        }
        return matching;
    }

    private static Map<String, String> keysByLowercase(Set<String> keyColumns) {
        var map = new LinkedHashMap<String, String>();
        if (keyColumns != null) {
            for (var key : keyColumns) {
                map.put(key.toLowerCase(Locale.ROOT), key);
            }
        }
        return map;
    }

    private static String lastSegment(String ref) {
        if (ref == null) {
            return "";
        }
        var normalized = ref.toLowerCase(Locale.ROOT).strip();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private static String targetName(CqlStatement statement) {
        return statement.target() == null ? "" : statement.target().normalized();
    }

    private UnrewritableRowSecurityException unrewritable(CqlStatement statement) {
        return new UnrewritableRowSecurityException(messages.get(
                "error.row_security_cassandra_unrewritable", targetName(statement)));
    }
}
