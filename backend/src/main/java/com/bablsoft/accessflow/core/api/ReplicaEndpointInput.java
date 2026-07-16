package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Admin input for one read-replica endpoint (AF-457), carried by
 * {@link CreateDatasourceCommand} / {@link UpdateDatasourceCommand}.
 *
 * <p>{@code id} identifies an existing {@code datasource_read_replicas} row to update; {@code null}
 * means "create a new endpoint". {@code password} semantics on update: {@code null} keeps the
 * stored secret, empty string clears it (primary-credential fallback), non-blank re-encrypts.
 */
public record ReplicaEndpointInput(
        UUID id,
        String jdbcUrl,
        String username,
        String password) {
}
