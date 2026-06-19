package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.core.api.SelectExecutionResult;

import java.util.List;

/**
 * Result of the {@code get_column_samples} MCP tool. The {@code rows} are already row-level-security
 * filtered and column-masked by the executor — masked columns carry the masked value (e.g.
 * {@code "***"}), never the raw one. {@code restricted} flags which columns were masked or redacted.
 * Mirrors {@code SampleRowsResponse} so the MCP and REST sample surfaces stay in lock-step.
 */
public record McpColumnSamples(
        List<Column> columns,
        List<List<Object>> rows,
        long rowCount,
        boolean truncated,
        long durationMs) {

    public record Column(String name, String type, boolean restricted) {
    }

    public static McpColumnSamples from(SelectExecutionResult result) {
        var columns = result.columns().stream()
                .map(c -> new Column(c.name(), c.typeName(), c.restricted()))
                .toList();
        return new McpColumnSamples(columns, result.rows(), result.rowCount(),
                result.truncated(), result.duration().toMillis());
    }
}
