package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param accessGrantRequestId the JIT {@code access_grant_request} this permission materialises, or
 *                             {@code null} for an admin-created row (#969)
 */
public record CreatePermissionCommand(
        UUID userId,
        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,
        Boolean canBreakGlass,
        Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        Instant expiresAt,
        UUID accessGrantRequestId
) {}
