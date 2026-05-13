package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.workflow.api.ReviewService;

import java.time.Instant;
import java.util.UUID;

public record McpPendingReview(
        UUID queryRequestId,
        UUID datasourceId,
        String datasourceName,
        UUID submittedByUserId,
        String submittedByEmail,
        String sqlText,
        String queryType,
        String justification,
        String aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary,
        int currentStage,
        Instant createdAt
) {
    public static McpPendingReview from(ReviewService.PendingReview r) {
        return new McpPendingReview(
                r.queryRequestId(),
                r.datasourceId(),
                r.datasourceName(),
                r.submittedByUserId(),
                r.submittedByEmail(),
                r.sqlText(),
                r.queryType().name(),
                r.justification(),
                r.aiRiskLevel() == null ? null : r.aiRiskLevel().name(),
                r.aiRiskScore(),
                r.aiSummary(),
                r.currentStage(),
                r.createdAt()
        );
    }
}
