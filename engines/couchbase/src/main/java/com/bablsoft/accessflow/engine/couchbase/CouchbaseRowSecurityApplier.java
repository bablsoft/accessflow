package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.engine.couchbase.SqlPlusPlusTokenizer.Kind;
import com.bablsoft.accessflow.engine.couchbase.SqlPlusPlusTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link CouchbaseStatement} at parity with
 * the SQL {@code RowSecurityRewriter}: SQL++ is SQL-shaped, so each matching
 * {@link RowSecurityDirective} is ANDed into the statement's WHERE clause with its comparison
 * values bound as <em>named parameters</em> ({@code $af_rls_n}) — never string-concatenated. An
 * empty value list becomes a literal {@code FALSE} (fail-closed deny-all). Only provably
 * filterable shapes are rewritten — a simple single-keyspace SELECT / UPDATE / DELETE. When a
 * policied keyspace is referenced from a shape the splice cannot reach (CTE, subquery,
 * JOIN/NEST/UNNEST, USE KEYS, set operations, multiple keyspaces, or a MERGE — simultaneously a
 * join-DML and a {@code WHEN NOT MATCHED THEN INSERT} carrier), the statement is rejected with
 * {@link UnrewritableRowSecurityException} (HTTP 422) rather than run unfiltered. INSERT/UPSERT
 * into a policied keyspace is rejected outright, mirroring the MongoDB engine. DDL is unaffected.
 */
class CouchbaseRowSecurityApplier {

    /** Keywords that can follow the WHERE clause at depth 0 (= the splice boundary). */
    private static final Set<String> WHERE_TAIL = Set.of(
            "GROUP", "LETTING", "HAVING", "WINDOW", "ORDER", "LIMIT", "OFFSET", "RETURNING");

    static final String PARAM_PREFIX = "af_rls_";

    record Applied(String sql, Map<String, Object> parameters, Set<UUID> appliedPolicyIds) {
    }

    private final EngineMessages messages;

    CouchbaseRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(CouchbaseStatement statement, List<RowSecurityDirective> directives) {
        var policied = policiedKeyspaces(statement, directives);
        if (policied.isEmpty()) {
            return new Applied(statement.sql(), Map.of(), Set.of());
        }
        switch (statement.kind()) {
            case DDL -> {
                // No rows are read or affected; parity with the SQL and MongoDB engines.
                return new Applied(statement.sql(), Map.of(), Set.of());
            }
            case INSERT, UPSERT -> {
                if (statement.target() != null
                        && policied.contains(statement.target().normalized())) {
                    throw new UnrewritableRowSecurityException(messages.get(
                            "error.row_security_couchbase_insert_unsupported",
                            statement.target().normalized()));
                }
                // INSERT/UPSERT … SELECT reading a policied keyspace would leak its rows.
                throw unrewritable(policied);
            }
            case MERGE -> throw unrewritable(policied);
            case SELECT, UPDATE, DELETE -> {
                if (statement.hasCte() || statement.hasSubquery() || statement.hasJoinLike()
                        || statement.hasUseKeys() || statement.hasSetOperation()
                        || statement.target() == null || statement.keyspaces().size() > 1) {
                    throw unrewritable(policied);
                }
            }
        }
        var matching = new ArrayList<RowSecurityDirective>();
        for (var directive : directives) {
            if (matchesKeyspace(directive.tableRef(), statement.target())) {
                matching.add(directive);
            }
        }
        var policyIds = new LinkedHashSet<UUID>();
        var parameters = new LinkedHashMap<String, Object>();
        var fragments = new ArrayList<String>(matching.size());
        var qualifier = statement.targetAlias() != null
                ? statement.targetAlias()
                : statement.target().lastSegment();
        for (var directive : matching) {
            fragments.add(toFragment(directive, qualifier, parameters));
            policyIds.add(directive.policyId());
        }
        var predicate = "(" + String.join(" AND ", fragments) + ")";
        return new Applied(splice(statement, predicate), Map.copyOf(parameters),
                Set.copyOf(policyIds));
    }

    // ---- predicate building -------------------------------------------------------------------

    private static String toFragment(RowSecurityDirective directive, String qualifier,
                                     Map<String, Object> parameters) {
        var values = directive.values();
        if (values.isEmpty()) {
            // Fail-closed: an unresolvable variable / empty list yields no rows.
            return "FALSE";
        }
        var column = escapeIdent(qualifier) + "." + escapeIdent(directive.columnName());
        var paramName = PARAM_PREFIX + parameters.size();
        return switch (directive.operator()) {
            case EQUALS -> bindScalar(column, "=", paramName, values, parameters);
            case NOT_EQUALS -> bindScalar(column, "!=", paramName, values, parameters);
            case LESS_THAN -> bindScalar(column, "<", paramName, values, parameters);
            case LESS_THAN_OR_EQUAL -> bindScalar(column, "<=", paramName, values, parameters);
            case GREATER_THAN -> bindScalar(column, ">", paramName, values, parameters);
            case GREATER_THAN_OR_EQUAL -> bindScalar(column, ">=", paramName, values, parameters);
            case IN -> bindList(column, "IN", paramName, values, parameters);
            case NOT_IN -> bindList(column, "NOT IN", paramName, values, parameters);
        };
    }

    private static String bindScalar(String column, String operator, String paramName,
                                     List<Object> values, Map<String, Object> parameters) {
        parameters.put(paramName, values.get(0));
        return column + " " + operator + " $" + paramName;
    }

    private static String bindList(String column, String operator, String paramName,
                                   List<Object> values, Map<String, Object> parameters) {
        parameters.put(paramName, List.copyOf(values));
        return column + " " + operator + " $" + paramName;
    }

    private static String escapeIdent(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    // ---- WHERE splice ---------------------------------------------------------------------------

    /**
     * Splices the RLS predicate into the statement text by token offsets: an existing top-level
     * WHERE expression is wrapped in parentheses and ANDed; otherwise a new WHERE clause is
     * inserted before the first depth-0 tail keyword (GROUP BY / ORDER BY / LIMIT / … ) or
     * appended at the end.
     */
    private static String splice(CouchbaseStatement statement, String predicate) {
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
            if (token.depth() == 0 && token.kind() == Kind.WORD
                    && WHERE_TAIL.contains(token.value())) {
                return token.start();
            }
        }
        return tokens.get(tokens.size() - 1).end();
    }

    // ---- keyspace matching ----------------------------------------------------------------------

    private Set<String> policiedKeyspaces(CouchbaseStatement statement,
                                          List<RowSecurityDirective> directives) {
        var policied = new LinkedHashSet<String>();
        if (directives == null || directives.isEmpty()) {
            return policied;
        }
        for (var keyspace : statement.keyspaces()) {
            for (var directive : directives) {
                if (matchesLastSegment(directive.tableRef(), keyspace)) {
                    policied.add(keyspace);
                    break;
                }
            }
        }
        return policied;
    }

    private static boolean matchesKeyspace(String tableRef, KeyspaceRef keyspace) {
        return keyspace != null && matchesLastSegment(tableRef, keyspace.normalized());
    }

    /**
     * A directive's {@code tableRef} matches a keyspace when the last dot-segment of each is the
     * same, case-insensitively (e.g. {@code "travel.inventory.users"} matches {@code "users"}) —
     * the collection-level semantics shared with the MongoDB engine.
     */
    private static boolean matchesLastSegment(String tableRef, String keyspace) {
        if (tableRef == null || keyspace == null) {
            return false;
        }
        return lastSegment(tableRef).equals(lastSegment(keyspace));
    }

    private static String lastSegment(String ref) {
        var normalized = ref.toLowerCase(Locale.ROOT).strip();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private UnrewritableRowSecurityException unrewritable(Set<String> policied) {
        return new UnrewritableRowSecurityException(messages.get(
                "error.row_security_couchbase_unrewritable", policied.iterator().next()));
    }
}
