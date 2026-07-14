package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookup of a user's permission on an API connector, for cross-module build-time validation
 * (e.g. the {@code requestgroups} module checking that a submitter may bundle an API-call member
 * before the group is reviewed). Mirrors {@code core.api.DatasourceUserPermissionLookupService}.
 */
public interface ApiConnectorPermissionLookupService {

    Optional<ApiConnectorPermissionLookupView> findFor(UUID connectorId, UUID userId);

    /**
     * The user's <em>direct</em> permission row only — group grants excluded. Used by the access
     * (JIT) module to decide whether a standing admin-granted row would be clobbered by a JIT
     * materialisation ({@code expiresAt == null} = standing).
     */
    Optional<ApiConnectorDirectPermissionView> findDirectFor(UUID connectorId, UUID userId);

    record ApiConnectorDirectPermissionView(
            UUID id,
            UUID connectorId,
            UUID userId,
            Instant expiresAt) {
    }

    record ApiConnectorPermissionLookupView(
            UUID connectorId,
            UUID userId,
            boolean canRead,
            boolean canWrite,
            boolean canBreakGlass,
            List<String> allowedOperations,
            Instant expiresAt) {
    }
}
