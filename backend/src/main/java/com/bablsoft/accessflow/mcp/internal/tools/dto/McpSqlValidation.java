package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.List;

/**
 * Result of the {@code validate_sql} MCP tool. Parse failures are reported as data ({@code valid =
 * false} with {@code parseError}), not as an MCP error, so an agent can iterate on a draft. When the
 * statement parses, {@code unknownTables} lists referenced tables absent from the schema the caller
 * can see — best-effort: {@code schemaChecked} is {@code false} when the customer database was
 * unreachable, in which case {@code unknownTables} is empty.
 */
public record McpSqlValidation(
        boolean valid,
        String queryType,
        List<String> referencedTables,
        boolean hasWhereClause,
        boolean hasLimitClause,
        List<String> unknownTables,
        boolean schemaChecked,
        String parseError) {

    public static McpSqlValidation invalid(String parseError) {
        return new McpSqlValidation(false, null, List.of(), false, false, List.of(), false, parseError);
    }

    public static McpSqlValidation valid(SqlParseResult result, List<String> unknownTables,
                                         boolean schemaChecked) {
        return new McpSqlValidation(
                true,
                result.type().name(),
                result.referencedTables().stream().sorted().toList(),
                result.hasWhereClause(),
                result.hasLimitClause(),
                List.copyOf(unknownTables),
                schemaChecked,
                null);
    }
}
