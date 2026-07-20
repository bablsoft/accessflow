package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read view of a per-user, per-connector permission grant ("share with team"). */
public record ApiConnectorPermissionView(
        UUID id,
        UUID connectorId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        boolean canOverrideVariables,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields,
        Instant createdAt) {
}
