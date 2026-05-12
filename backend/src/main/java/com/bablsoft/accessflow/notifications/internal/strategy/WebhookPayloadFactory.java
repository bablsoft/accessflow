package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the webhook JSON envelope per {@code docs/08-notifications.md}.
 */
@Component
@RequiredArgsConstructor
class WebhookPayloadFactory {

    private final ObjectMapper objectMapper;

    String buildBody(NotificationContext ctx) {
        var queryRequest = new LinkedHashMap<String, Object>();
        queryRequest.put("id", ctx.queryRequestId());
        queryRequest.put("sql_preview", ctx.sqlPreview200());
        queryRequest.put("query_type", ctx.queryType());
        queryRequest.put("risk_level", ctx.riskLevel());
        queryRequest.put("risk_score", ctx.riskScore());
        queryRequest.put("submitter_email", ctx.submitterEmail());
        queryRequest.put("datasource_name", ctx.datasourceName());
        queryRequest.put("justification", ctx.justification());
        queryRequest.put("review_url", ctx.reviewUrl() != null ? ctx.reviewUrl().toString() : null);
        queryRequest.put("reviewer_id", ctx.reviewerUserId());
        queryRequest.put("reviewer_display_name", ctx.reviewerDisplayName());

        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("event", ctx.eventType());
        envelope.put("timestamp", ctx.occurredAt() != null ? ctx.occurredAt() : Instant.now());
        envelope.put("organization_id", ctx.organizationId());
        envelope.put("query_request", queryRequest);
        return objectMapper.writeValueAsString(envelope);
    }

    String buildTestBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", NotificationEventType.TEST);
        body.put("timestamp", Instant.now());
        return objectMapper.writeValueAsString(body);
    }
}
