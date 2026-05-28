package com.bablsoft.accessflow.core.api;

/**
 * Live-values request for the {@code POST /datasources/{id}/test-replica} endpoint. {@code jdbcUrl}
 * and {@code username} are required (the caller is exercising a candidate replica config);
 * {@code password} may be {@code null} to fall back to the currently-persisted replica password —
 * useful for changing URL/user without re-typing the secret.
 */
public record TestReplicaCommand(
        String jdbcUrl,
        String username,
        String password
) {}
