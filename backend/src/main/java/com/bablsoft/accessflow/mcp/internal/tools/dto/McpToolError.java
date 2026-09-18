package com.bablsoft.accessflow.mcp.internal.tools.dto;

/** The structured {@code { code, message }} error shape MCP tools return (docs/13-mcp.md §4). */
public record McpToolError(String code, String message) {

    public static final String PERMISSION_DENIED = "permission_denied";

    public static McpToolError permissionDenied(String message) {
        return new McpToolError(PERMISSION_DENIED, message);
    }
}
