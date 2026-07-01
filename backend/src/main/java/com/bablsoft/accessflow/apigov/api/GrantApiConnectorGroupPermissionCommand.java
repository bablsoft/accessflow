package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Grant (or update) a user group's access to an API connector (AF-530). */
public record GrantApiConnectorGroupPermissionCommand(
        UUID groupId,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {
}
