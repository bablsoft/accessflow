package com.partqam.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatePermissionRequest(
        @NotNull UUID userId,
        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,
        @Min(1) Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        Instant expiresAt
) {}
