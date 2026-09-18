package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/**
 * Decides whether an identity may invoke an MCP tool (#872). Consulted by the {@code mcp} module
 * around every registered tool callback, on the request thread, before the tool body runs.
 *
 * <p>Defaults closed: a name that is not in the {@link McpToolName} catalog is denied for
 * everyone, so a tool registered without a catalog value can never be invoked past this check.
 * A person (no {@code service_accounts} row) and a service account whose
 * {@code mcp_tool_allow_list} is {@code NULL} are allowed every tool; an empty list allows none;
 * otherwise the wire name must be listed.
 */
public interface ServiceAccountToolPolicyService {

    boolean isAllowed(UUID userId, String toolName);
}
