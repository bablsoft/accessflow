package com.bablsoft.accessflow.sqlreview.api;

import java.time.Instant;
import java.util.UUID;

/** One stored custom rule of an organization (#1010). */
public record SqlReviewCustomRuleView(
        UUID id,
        UUID organizationId,
        String ruleId,
        String name,
        String description,
        String message,
        SqlRuleCategory category,
        SqlReviewSeverity defaultSeverity,
        boolean enabled,
        SqlRuleCondition condition,
        Instant createdAt,
        Instant updatedAt
) {
}
