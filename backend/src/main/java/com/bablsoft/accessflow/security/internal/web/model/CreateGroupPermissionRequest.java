package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateGroupPermissionRequest(
        @NotNull(message = "{validation.group_id.required}") UUID groupId,
        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,
        Boolean canBreakGlass,
        @Min(value = 1, message = "{validation.row_limit.min}") Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<@NotBlank(message = "{validation.restricted_columns.item_blank}") String> restrictedColumns,
        Instant expiresAt
) {}
