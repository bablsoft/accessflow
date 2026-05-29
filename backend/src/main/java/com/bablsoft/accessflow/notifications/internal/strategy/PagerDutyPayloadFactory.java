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
        return details;
    }

    private static String dedupKey(NotificationContext ctx) {
        return "accessflow-" + ctx.organizationId() + "-" + ctx.queryRequestId();
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
            case REVIEW_TIMEOUT -> "AccessFlow: review timed out for a query on " + datasource;
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
