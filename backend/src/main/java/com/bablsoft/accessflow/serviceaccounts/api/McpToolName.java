package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * The catalog of MCP tool names a service account's allow-list can reference (#868, enforced in
 * #872). It lives here rather than in {@code mcp.api} so that {@code mcp} imports
 * {@code serviceaccounts.api} and never the reverse — the admin-side validation of an allow-list
 * (#871) needs the catalog too, and putting it in {@code mcp.api} would create a module cycle.
 * {@code McpToolNameParityTest} keeps this enum equal to the {@code @Tool(name = …)} set.
 */
public enum McpToolName {
    LIST_DATASOURCES("list_datasources"),
    GET_DATASOURCE_SCHEMA("get_datasource_schema"),
    LIST_MY_QUERIES("list_my_queries"),
    GET_QUERY_STATUS("get_query_status"),
    GET_QUERY_RESULT("get_query_result"),
    SUBMIT_QUERY("submit_query"),
    CANCEL_QUERY("cancel_query"),
    LIST_PENDING_REVIEWS("list_pending_reviews"),
    REVIEW_QUERY("review_query"),
    VALIDATE_SQL("validate_sql"),
    GET_COLUMN_SAMPLES("get_column_samples"),
    GET_AUDIT_LOG("get_audit_log");

    private final String toolName;

    McpToolName(String toolName) {
        this.toolName = toolName;
    }

    /** The wire name the MCP server advertises in {@code tools/list}. */
    public String toolName() {
        return toolName;
    }

    /** Resolves a wire name; empty for an unknown (or null) name — callers deny, never default. */
    public static Optional<McpToolName> fromToolName(String toolName) {
        return Arrays.stream(values()).filter(v -> v.toolName.equals(toolName)).findFirst();
    }
}
