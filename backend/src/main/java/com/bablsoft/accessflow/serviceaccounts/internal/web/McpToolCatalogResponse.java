package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.McpToolName;

import java.util.Arrays;
import java.util.List;

/**
 * The MCP tool names an allow-list may reference (#875) — the wire names, in catalog order, so the
 * admin UI builds its checkbox list from the same enum the enforcement reads.
 */
public record McpToolCatalogResponse(List<String> tools) {

    public static McpToolCatalogResponse current() {
        return new McpToolCatalogResponse(Arrays.stream(McpToolName.values()).map(McpToolName::toolName).toList());
    }
}
