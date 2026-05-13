package com.bablsoft.accessflow.mcp.internal.tools.dto;

public record McpQueryResult(
        String columnsJson,
        String rowsJson,
        long rowCount,
        boolean truncated,
        int durationMs
) {}
