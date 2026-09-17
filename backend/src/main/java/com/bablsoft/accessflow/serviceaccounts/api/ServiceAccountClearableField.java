package com.bablsoft.accessflow.serviceaccounts.api;

/**
 * The UI-owned fields an update may explicitly reset (#871). Every field of an update is
 * null-means-unchanged, so a client that omits a field can never widen anything by accident;
 * clearing — and in particular {@code MCP_TOOL_ALLOW_LIST}, which means "every tool" once null —
 * has to be asked for by name.
 */
public enum ServiceAccountClearableField {
    DESCRIPTION,
    OWNER_USER_ID,
    MCP_TOOL_ALLOW_LIST,
    RATE_LIMIT_PER_MINUTE,
    RATE_LIMIT_PER_DAY
}
