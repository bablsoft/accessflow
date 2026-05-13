package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.core.api.QueryDetailView;

import java.time.Instant;
import java.util.UUID;

public record McpQueryDetail(
        UUID id,
        UUID datasourceId,
        String datasourceName,
        UUID submittedByUserId,
        String submittedByEmail,
        String queryType,
        String status,
        String sqlText,
        String justification,
        String aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary,
        Long rowsAffected,
        Integer durationMs,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static McpQueryDetail from(QueryDetailView v) {
        return new McpQueryDetail(
                v.id(),
                v.datasourceId(),
                v.datasourceName(),
                v.submittedByUserId(),
                v.submittedByEmail(),
                v.queryType().name(),
                v.status().name(),
                v.sqlText(),
                v.justification(),
                v.aiAnalysis() == null || v.aiAnalysis().riskLevel() == null
                        ? null : v.aiAnalysis().riskLevel().name(),
                v.aiAnalysis() == null ? null : v.aiAnalysis().riskScore(),
                v.aiAnalysis() == null ? null : v.aiAnalysis().summary(),
                v.rowsAffected(),
                v.durationMs(),
                v.errorMessage(),
                v.createdAt(),
                v.updatedAt()
        );
    }
}
