package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Grant (or update) a user's access to an API connector. */
public record GrantApiConnectorPermissionCommand(
        UUID userId,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {
}
