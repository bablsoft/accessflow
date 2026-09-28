package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the JSON body sent to a decision hook (#945). Keys are spelled out here rather than
 * derived from a record so the wire contract in {@code docs/04-api-spec.md} is exactly what this
 * class writes, and unknown values are omitted rather than sent as {@code null}.
 *
 * <p>The SQL text is added only when the hook has {@code include_sql} on: it can carry literal
 * values, and sending those to a third-party endpoint is a disclosure the operator opts into.
 */
@Component
@RequiredArgsConstructor
class DecisionHookPayloadFactory {

    static final String EVENT_QUERY_DECISION = "QUERY_DECISION";
    static final String EVENT_TEST = "QUERY_DECISION_TEST";

    private final UserQueryService userQueryService;
    private final DatasourceAdminService datasourceAdminService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    byte[] forQuery(UUID requestId, QueryRequestSnapshot query, ConditionContext context,
                    AiOutcome aiOutcome, boolean includeSql) {
        var body = header(requestId, EVENT_QUERY_DECISION, false, query.organizationId());
        body.put("query_request_id", query.id().toString());
        body.put("submitter", submitter(query, context));
        putIfPresent(body, "datasource", datasource(query.datasourceId(), query.organizationId()));
        var q = new LinkedHashMap<String, Object>();
        putIfPresent(q, "type", context.queryType() == null ? null : context.queryType().name());
        q.put("referenced_tables", sorted(context.referencedTables()));
        if (context.shapesAnalyzed()) {
            q.put("shapes", context.queryShapes().stream().map(Enum::name).sorted().toList());
        }
        q.put("has_where_clause", context.hasWhereClause());
        q.put("has_limit_clause", context.hasLimitClause());
        q.put("transactional", context.transactional());
        if (includeSql) {
            q.put("sql", query.sqlText());
        }
        body.put("query", q);
        var ai = new LinkedHashMap<String, Object>();
        ai.put("outcome", aiOutcome.name());
        putIfPresent(ai, "risk_level", context.riskLevel() == null ? null : context.riskLevel().name());
        if (context.riskScore() >= 0) {
            ai.put("risk_score", context.riskScore());
        }
        body.put("ai", ai);
        var cost = new LinkedHashMap<String, Object>();
        putIfPresent(cost, "estimated_rows", context.estimatedRows());
        putIfPresent(cost, "estimated_bytes_scanned", context.estimatedBytesScanned());
        putIfPresent(cost, "scan_type", context.scanType());
        body.put("cost_estimate", cost);
        var client = new LinkedHashMap<String, Object>();
        putIfPresent(client, "ip", context.requesterIpAddress());
        putIfPresent(client, "user_agent", context.requesterUserAgent());
        client.put("ci_cd_origin", context.ciCdOrigin());
        body.put("client", client);
        return objectMapper.writeValueAsBytes(body);
    }

    /** A request no query stands behind, for the admin test button. */
    byte[] forTest(UUID requestId, UUID organizationId, UUID datasourceId) {
        var body = header(requestId, EVENT_TEST, true, organizationId);
        if (datasourceId != null) {
            putIfPresent(body, "datasource", datasource(datasourceId, organizationId));
        }
        var q = new LinkedHashMap<String, Object>();
        q.put("type", "SELECT");
        q.put("referenced_tables", List.of());
        q.put("has_where_clause", false);
        q.put("has_limit_clause", false);
        q.put("transactional", false);
        body.put("query", q);
        body.put("ai", Map.of("outcome", AiOutcome.SKIPPED.name()));
        body.put("cost_estimate", Map.of());
        body.put("client", Map.of("ci_cd_origin", false));
        return objectMapper.writeValueAsBytes(body);
    }

    private LinkedHashMap<String, Object> header(UUID requestId, String event, boolean test,
                                                 UUID organizationId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("request_id", requestId.toString());
        body.put("event", event);
        body.put("test", test);
        body.put("timestamp", clock.instant().toString());
        body.put("organization_id", organizationId.toString());
        return body;
    }

    private Map<String, Object> submitter(QueryRequestSnapshot query, ConditionContext context) {
        var submitter = new LinkedHashMap<String, Object>();
        submitter.put("user_id", query.submittedByUserId().toString());
        userQueryService.findById(query.submittedByUserId()).ifPresent(user -> {
            putIfPresent(submitter, "email", user.email());
            putIfPresent(submitter, "display_name", user.displayName());
            putIfPresent(submitter, "principal_type",
                    user.principalType() == null ? null : user.principalType().name());
        });
        putIfPresent(submitter, "role", context.requesterRoleName());
        submitter.put("group_ids", context.requesterGroupIds() == null ? List.of()
                : context.requesterGroupIds().stream().map(UUID::toString).sorted().toList());
        putIfPresent(submitter, "on_behalf_of_user_id",
                query.onBehalfOfUserId() == null ? null : query.onBehalfOfUserId().toString());
        return submitter;
    }

    private Map<String, Object> datasource(UUID datasourceId, UUID organizationId) {
        try {
            var view = datasourceAdminService.getForAdmin(datasourceId, organizationId);
            var datasource = new LinkedHashMap<String, Object>();
            datasource.put("id", view.id().toString());
            putIfPresent(datasource, "name", view.name());
            putIfPresent(datasource, "db_type", view.dbType() == null ? null : view.dbType().name());
            putIfPresent(datasource, "environment",
                    view.environment() == null ? null : view.environment().name());
            return datasource;
        } catch (DatasourceNotFoundException ex) {
            return null;
        }
    }

    private static List<String> sorted(java.util.Set<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
