package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutySeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds <a href="https://developer.pagerduty.com/docs/events-api-v2/trigger-events/">PagerDuty
 * Events API v2</a> {@code enqueue} payloads. The {@code dedup_key} is stable per query request so
 * re-triggers (and any future {@code resolve}) collapse into a single PagerDuty incident.
 */
@Component
@RequiredArgsConstructor
class PagerDutyPayloadFactory {

    private static final String SOURCE_FALLBACK = "accessflow";
    private static final int SUMMARY_MAX_LENGTH = 1024;

    private final ObjectMapper objectMapper;

    String buildEventBody(NotificationContext ctx, PagerDutyChannelConfig config) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("routing_key", config.routingKeyPlain());
        envelope.put("event_action", "trigger");
        envelope.put("dedup_key", dedupKey(ctx));
        envelope.put("client", "AccessFlow");
        if (ctx.reviewUrl() != null) {
            envelope.put("client_url", ctx.reviewUrl().toString());
        }
        envelope.put("payload", buildPayload(ctx, config));
        return objectMapper.writeValueAsString(envelope);
    }

    String buildTestBody(PagerDutyChannelConfig config) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("summary", "AccessFlow notification channel test");
        payload.put("source", SOURCE_FALLBACK);
        payload.put("severity", PagerDutySeverity.INFO.wireValue());

        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("routing_key", config.routingKeyPlain());
        envelope.put("event_action", "trigger");
        envelope.put("dedup_key", "accessflow-test");
        envelope.put("client", "AccessFlow");
        envelope.put("payload", payload);
        return objectMapper.writeValueAsString(envelope);
    }

    private Map<String, Object> buildPayload(NotificationContext ctx, PagerDutyChannelConfig config) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("summary", truncateSummary(summaryLine(ctx)));
        payload.put("source", source(ctx));
        payload.put("severity", config.defaultSeverity().wireValue());
        payload.put("timestamp", ctx.occurredAt() != null ? ctx.occurredAt() : Instant.now());
        if (ctx.organizationId() != null) {
            payload.put("group", ctx.organizationId().toString());
        }
        if (ctx.datasourceName() != null) {
            payload.put("component", ctx.datasourceName());
        }
        payload.put("class", ctx.eventType().name());
        payload.put("custom_details", buildCustomDetails(ctx));
        return payload;
    }

    private static Map<String, Object> buildCustomDetails(NotificationContext ctx) {
        var details = new LinkedHashMap<String, Object>();
        if (ctx.queryRequestId() != null) {
            details.put("query_id", ctx.queryRequestId().toString());
        }
        if (ctx.queryType() != null) {
            details.put("query_type", ctx.queryType().name());
        }
        if (ctx.riskLevel() != null) {
            details.put("risk_level", ctx.riskLevel().name());
        }
        if (ctx.riskScore() != null) {
            details.put("risk_score", ctx.riskScore());
        }
        if (ctx.submitterEmail() != null) {
            details.put("submitter_email", ctx.submitterEmail());
        }
        if (ctx.datasourceName() != null) {
            details.put("datasource_name", ctx.datasourceName());
        }
        if (ctx.justification() != null) {
            details.put("justification", ctx.justification());
        }
        if (ctx.reviewUrl() != null) {
            details.put("review_url", ctx.reviewUrl().toString());
        }
        if (ctx.eventType() == NotificationEventType.REVIEW_TIMEOUT
                && ctx.approvalTimeoutHours() != null) {
            details.put("approval_timeout_hours", ctx.approvalTimeoutHours());
        }
        // #695: break-glass deployments — the pipeline already rides in datasource_name/component.
        if (ctx.deploymentRequestId() != null) {
            details.put("deployment_id", ctx.deploymentRequestId().toString());
            if (ctx.environmentName() != null) {
                details.put("environment", ctx.environmentName());
            }
            if (ctx.deploymentVersion() != null) {
                details.put("version", ctx.deploymentVersion());
            }
            if (ctx.deploymentOutcome() != null) {
                details.put("outcome", ctx.deploymentOutcome().name());
            }
        }
        if (ctx.isSchemaChangeEvent()) {
            if (ctx.schemaChangePromotionId() != null) {
                details.put("schema_change_promotion_id", ctx.schemaChangePromotionId().toString());
            }
            if (ctx.schemaChangeSetName() != null) {
                details.put("change_set", ctx.schemaChangeSetName());
            }
            if (ctx.environmentName() != null) {
                details.put("environment", ctx.environmentName());
            }
            if (ctx.schemaChangeStatus() != null) {
                details.put("status", ctx.schemaChangeStatus());
            }
            if (ctx.driftNewFindingCount() != null) {
                details.put("new_finding_count", ctx.driftNewFindingCount());
            }
        }
        if (ctx.dataBudget() != null) {
            var budget = ctx.dataBudget();
            details.put("budget_name", budget.budgetName());
            details.put("used_percent", budget.usedPercent());
            details.put("window_minutes", budget.windowMinutes());
        }
        if (ctx.anomalyId() != null) {
            details.put("anomaly_id", ctx.anomalyId().toString());
            if (ctx.anomalyFeature() != null) {
                details.put("anomaly_feature", ctx.anomalyFeature());
            }
            if (ctx.anomalyScore() != null) {
                details.put("anomaly_score", ctx.anomalyScore());
            }
            if (ctx.anomalyUserLabel() != null) {
                details.put("anomaly_user", ctx.anomalyUserLabel());
            }
        }
        return details;
    }

    private static String dedupKey(NotificationContext ctx) {
        // queryRequestId is null for non-query events (anomalies, deployments, connector-scoped
        // alerts); fall back to the anomaly id, then the deployment-request id (#695 — before the
        // datasourceId fallback, or two break-glass deployments on one pipeline would collapse
        // into a single incident), then the connector id (carried in datasourceId), so each
        // subject is its own PagerDuty incident rather than all collapsing into one.
        var subject = ctx.queryRequestId() != null ? ctx.queryRequestId()
                : ctx.anomalyId() != null ? ctx.anomalyId()
                : ctx.deploymentRequestId() != null ? ctx.deploymentRequestId()
                : ctx.schemaChangePromotionId() != null ? ctx.schemaChangePromotionId()
                : ctx.datasourceId() != null ? ctx.datasourceId() : "none";
        // The event type is part of the key (#622) because one subject can raise genuinely
        // different incidents: a CRITICAL-risk query that then stalls in review would otherwise
        // fold its REVIEW_ESCALATED into the still-open AI_HIGH_RISK incident and page nobody —
        // and a stalled critical query is precisely the case REVIEW_STALLED exists for. Repeats of
        // the same event on the same subject still dedupe, which is what dedup is for.
        return "accessflow-" + ctx.organizationId() + "-" + ctx.eventType().name() + "-" + subject;
    }

    private static String source(NotificationContext ctx) {
        return (ctx.datasourceName() == null || ctx.datasourceName().isBlank())
                ? SOURCE_FALLBACK
                : ctx.datasourceName();
    }

    private static String summaryLine(NotificationContext ctx) {
        var datasource = (ctx.datasourceName() == null || ctx.datasourceName().isBlank())
                ? "a datasource"
                : ctx.datasourceName();
        return switch (ctx.eventType()) {
            case AI_HIGH_RISK -> "AccessFlow: AI flagged a CRITICAL-risk query on " + datasource;
            case QUERY_ESCALATED -> "AccessFlow: a routing policy escalated a query on " + datasource;
            // #622: distinct from QUERY_ESCALATED — this one means nobody decided in time.
            // (REVIEW_NUDGE has no PagerDutyTrigger and so never reaches here: a reminder is not
            // an incident.)
            case REVIEW_ESCALATED ->
                    "AccessFlow: a query on " + datasource + " has had no review decision";
            case REVIEW_TIMEOUT -> "AccessFlow: review timed out for a query on " + datasource;
            case ANOMALY_DETECTED -> "AccessFlow: behavioral anomaly detected on " + datasource;
            case BREAK_GLASS_EXECUTED -> "AccessFlow: break-glass query executed on " + datasource;
            // #695: the only deployment event with a PagerDuty trigger; datasource is the pipeline.
            // Spelled out because the default below says "for a query".
            case DEPLOYMENT_BREAK_GLASS_EXECUTED ->
                    "AccessFlow: break-glass deployment executed on pipeline " + datasource;
            // #882: no PagerDutyTrigger maps any schema-change event, so none of these page today —
            // spelled out anyway so a trigger added later never falls into "for a query" below.
            case SCHEMA_CHANGE_PROMOTION_SUBMITTED ->
                    "AccessFlow: schema change awaiting review on pipeline " + datasource;
            case SCHEMA_CHANGE_PROMOTION_APPLIED ->
                    "AccessFlow: schema change applied on pipeline " + datasource;
            case SCHEMA_CHANGE_PROMOTION_FAILED ->
                    "AccessFlow: schema change failed on pipeline " + datasource;
            case SCHEMA_DRIFT_DETECTED -> "AccessFlow: schema drift detected on pipeline " + datasource;
            // #942: no PagerDutyTrigger maps the data-budget events, so neither pages — spelled
            // out so one added later never falls into "for a query" below.
            case DATA_BUDGET_THRESHOLD_REACHED ->
                    "AccessFlow: data budget warning threshold reached on " + datasource;
            case DATA_BUDGET_EXHAUSTED -> "AccessFlow: data budget exhausted on " + datasource;
            case API_CONNECTOR_OAUTH2_TOKEN_FAILED ->
                    "AccessFlow: OAuth2 token fetch repeatedly failing for connector " + datasource;
            default -> "AccessFlow: " + ctx.eventType().name() + " for a query on " + datasource;
        };
    }

    private static String truncateSummary(String summary) {
        if (summary.length() <= SUMMARY_MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, SUMMARY_MAX_LENGTH);
    }
}
