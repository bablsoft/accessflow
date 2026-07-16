package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Live-values request for the {@code POST /datasources/{id}/test-replica} endpoint. {@code jdbcUrl}
 * and {@code username} are required (the caller is exercising a candidate replica config);
 * {@code password} may be {@code null} to fall back to the persisted password of the endpoint
 * identified by {@code replicaId} — useful for changing URL/user without re-typing the secret.
 */
public record TestReplicaCommand(
        String jdbcUrl,
        String username,
        String password,
        UUID replicaId
) {
    /** Backward-compatible constructor without a {@code replicaId} (no persisted-password fallback). */
    public TestReplicaCommand(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, null);
    }
}
