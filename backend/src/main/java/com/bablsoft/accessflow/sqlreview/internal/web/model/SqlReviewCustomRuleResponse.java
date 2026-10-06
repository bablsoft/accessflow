package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** One stored custom rule on the wire (#1010); {@code condition} is the JSON tree. */
public record SqlReviewCustomRuleResponse(
        UUID id,
        UUID organizationId,
        String ruleId,
        String name,
        String description,
        String message,
        SqlRuleCategory category,
        SqlReviewSeverity defaultSeverity,
        boolean enabled,
        JsonNode condition,
        Instant createdAt,
        Instant updatedAt
) {
    public static SqlReviewCustomRuleResponse from(SqlReviewCustomRuleView view, JsonNode condition) {
        return new SqlReviewCustomRuleResponse(view.id(), view.organizationId(), view.ruleId(), view.name(), view.description(),
                view.message(), view.category(), view.defaultSeverity(), view.enabled(), condition,
                view.createdAt(), view.updatedAt());
    }
}
