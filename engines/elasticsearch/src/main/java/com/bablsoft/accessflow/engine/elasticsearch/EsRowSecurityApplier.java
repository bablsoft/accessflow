package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link EsCommand} at parity with the SQL
 * {@code RowSecurityRewriter} and the Mongo {@code $match} injection: each {@link RowSecurityDirective}
 * whose {@code tableRef} matches the target index becomes a Query-DSL {@code filter} clause
 * ({@code EQUALS → term}, {@code IN → terms}, range operators → {@code range}, …; an empty value
 * list is the fail-closed {@code must_not match_all}). Clauses are spliced into a {@code bool} that
 * wraps the user query in {@code must} (never merged, so the rewrite is provably non-widening):
 * <ul>
 *   <li>search / count / update_by_query / delete_by_query (and lowered get / mget) → query wrapped;</li>
 *   <li>index / bulk into a policied index → rejected (HTTP 422), since a write cannot be filtered;</li>
 *   <li>DDL (create_index / put_mapping / delete_index) → unaffected.</li>
 * </ul>
 * Row-security columns must be exact-match {@code keyword} fields — a {@code term} on an analysed
 * {@code text} field matches tokens, not the literal value (surfaced via introspection field types).
 */
class EsRowSecurityApplier {

    record Applied(EsCommand command, Set<UUID> appliedPolicyIds) {
    }

    private final EngineMessages messages;

    EsRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(EsCommand command, List<RowSecurityDirective> directives) {
        var matching = new ArrayList<RowSecurityDirective>();
        if (directives != null) {
            for (var directive : directives) {
                if (matchesIndex(directive.tableRef(), command.index())) {
                    matching.add(directive);
                }
            }
        }
        if (matching.isEmpty()) {
            return new Applied(command, Set.of());
        }
        return switch (command.operation()) {
            case SEARCH, COUNT, UPDATE_BY_QUERY, DELETE_BY_QUERY -> wrap(command, matching);
            case INDEX, BULK -> throw new UnrewritableRowSecurityException(
                    messages.get("error.row_security_search_insert_unsupported", command.index()));
            case CREATE_INDEX, PUT_MAPPING, DELETE_INDEX -> new Applied(command, Set.of());
        };
    }

    private Applied wrap(EsCommand command, List<RowSecurityDirective> matching) {
        var filters = new ArrayList<JsonNode>(matching.size());
        var policyIds = new LinkedHashSet<UUID>();
        for (var directive : matching) {
            filters.add(toClause(directive));
            policyIds.add(directive.policyId());
        }
        var userQuery = command.query() != null ? command.query() : EsJson.matchAll();
        return new Applied(command.withQuery(EsJson.boolFilter(userQuery, filters)), policyIds);
    }

    private static JsonNode toClause(RowSecurityDirective directive) {
        var field = directive.columnName();
        var values = directive.values();
        if (values.isEmpty()) {
            // Fail-closed: a query that matches no document.
            return EsJson.notMatchAll();
        }
        var first = values.get(0);
        return switch (directive.operator()) {
            case EQUALS -> EsJson.term(field, first);
            case NOT_EQUALS -> EsJson.not(EsJson.term(field, first));
            case LESS_THAN -> EsJson.range(field, "lt", first);
            case LESS_THAN_OR_EQUAL -> EsJson.range(field, "lte", first);
            case GREATER_THAN -> EsJson.range(field, "gt", first);
            case GREATER_THAN_OR_EQUAL -> EsJson.range(field, "gte", first);
            case IN -> EsJson.terms(field, values);
            case NOT_IN -> EsJson.not(EsJson.terms(field, values));
        };
    }

    /**
     * A directive's {@code tableRef} matches the index when the last dot-segment (e.g.
     * {@code "es.logs" → "logs"}) equals the index name / pattern, case-insensitively. Matching is
     * on the literal target string: a policy keyed to a concrete index does not inject when the user
     * queries a broader wildcard pattern (documented allow-list caveat).
     */
    static boolean matchesIndex(String tableRef, String index) {
        if (tableRef == null || index == null) {
            return false;
        }
        var ref = tableRef.toLowerCase(Locale.ROOT).trim();
        int dot = ref.lastIndexOf('.');
        var refIndex = dot >= 0 ? ref.substring(dot + 1) : ref;
        return refIndex.equals(index.toLowerCase(Locale.ROOT).trim());
    }
}
