package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * A full replace, except the secret.
 *
 * @param datasourceId {@code null} for the organization default
 * @param secret       a new signing secret, or {@code null} to keep the stored one
 */
public record UpdateDecisionHookCommand(
        UUID datasourceId,
        String name,
        String endpointUrl,
        int timeoutMs,
        String secret,
        boolean includeSql,
        boolean enabled) {
}
