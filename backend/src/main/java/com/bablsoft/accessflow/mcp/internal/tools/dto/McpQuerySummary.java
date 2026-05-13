package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.core.api.QueryListItemView;

import java.time.Instant;
import java.util.UUID;

public record McpQuerySummary(
        UUID id,
        UUID datasourceId,
        String datasourceName,
        String queryType,
        String status,
        String aiRiskLevel,
        Integer aiRiskScore,
        Instant createdAt
) {
    public static McpQuerySummary from(QueryListItemView v) {
        return new McpQuerySummary(
                v.id(),
                v.datasourceId(),
                v.datasourceName(),
                v.queryType().name(),
                v.status().name(),
                v.aiRiskLevel() == null ? null : v.aiRiskLevel().name(),
                v.aiRiskScore(),
                v.createdAt()
        );
    }
}
