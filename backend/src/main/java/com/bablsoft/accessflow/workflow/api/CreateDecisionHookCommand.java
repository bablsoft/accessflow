package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * @param datasourceId {@code null} for the organization default
 * @param secret       the HMAC signing secret in plain text; encrypted before it is stored
 */
public record CreateDecisionHookCommand(
        UUID organizationId,
        UUID datasourceId,
        String name,
        String endpointUrl,
        int timeoutMs,
        String secret,
        boolean includeSql,
        boolean enabled) {
}
