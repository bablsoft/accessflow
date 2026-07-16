package com.bablsoft.accessflow.core.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One read-replica endpoint of a datasource (AF-457). Carries only the encrypted password; never
 * serialized to a public API response. {@code username} / {@code passwordEncrypted} may be
 * {@code null}, in which case the primary datasource credentials are reused for the replica pool.
 */
public record ReadReplicaEndpoint(
        UUID id,
        String jdbcUrl,
        String username,
        String passwordEncrypted) {

    public ReadReplicaEndpoint {
        Objects.requireNonNull(id, "id");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
    }
}
