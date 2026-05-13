package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.workflow.api.ReviewService;

import java.util.UUID;

public record McpReviewOutcome(
        UUID decisionId,
        String decision,
        String resultingStatus,
        boolean idempotentReplay
) {
    public static McpReviewOutcome from(ReviewService.DecisionOutcome o) {
        return new McpReviewOutcome(
                o.decisionId(),
                o.decision().name(),
                o.resultingStatus().name(),
                o.wasIdempotentReplay()
        );
    }
}
