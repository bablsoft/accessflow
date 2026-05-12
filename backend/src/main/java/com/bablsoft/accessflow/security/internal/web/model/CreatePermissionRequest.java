package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatePermissionRequest(
        @NotNull(message = "{validation.user_id.required}") UUID userId,
        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,
        @Min(value = 1, message = "{validation.row_limit.min}") Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<@NotBlank(message = "{validation.restricted_columns.item_blank}") String> restrictedColumns,
        Instant expiresAt
) {}
