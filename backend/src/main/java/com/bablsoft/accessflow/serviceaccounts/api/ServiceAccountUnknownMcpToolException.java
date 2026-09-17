package com.bablsoft.accessflow.serviceaccounts.api;

/** An allow-list entry is not a catalog MCP tool name (#871) — 422. */
public final class ServiceAccountUnknownMcpToolException extends RuntimeException {

    private final String tool;

    public ServiceAccountUnknownMcpToolException(String tool) {
        super("Unknown MCP tool: " + tool);
        this.tool = tool;
    }

    public String tool() {
        return tool;
    }
}
