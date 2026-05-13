package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.core.api.DatasourceView;

import java.util.UUID;

public record McpDatasourceSummary(
        UUID id,
        String name,
        String dbType,
        String host,
        Integer port,
        String databaseName,
        boolean active,
        boolean requireReviewReads,
        boolean requireReviewWrites
) {
    public static McpDatasourceSummary from(DatasourceView view) {
        return new McpDatasourceSummary(
                view.id(),
                view.name(),
                view.dbType().name(),
                view.host(),
                view.port(),
                view.databaseName(),
                view.active(),
                view.requireReviewReads(),
                view.requireReviewWrites()
        );
    }
}
