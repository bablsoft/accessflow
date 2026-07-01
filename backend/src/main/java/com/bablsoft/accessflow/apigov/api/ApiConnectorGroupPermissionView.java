package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read view of a per-group, per-connector permission grant (AF-530). */
public record ApiConnectorGroupPermissionView(
        UUID id,
        UUID connectorId,
        UUID groupId,
        String groupName,
        long memberCount,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields,
        Instant createdAt) {
}
