package com.bablsoft.accessflow.mcp.internal.tools;

/**
 * Raised when an MCP tool is invoked without an authenticated principal. The MCP transport's
 * error wrapper converts this to a structured {@code unauthenticated} response for the caller.
 */
class McpAuthenticationException extends RuntimeException {

    McpAuthenticationException(String message) {
        super(message);
    }
}
