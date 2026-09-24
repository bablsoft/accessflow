package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatePermissionRequest(
        @NotNull(message = "{validation.user_id.required}") UUID userId,
        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,
        Boolean canBreakGlass,
        @Min(value = 1, message = "{validation.row_limit.min}") Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<@NotBlank(message = "{validation.restricted_columns.item_blank}") String> restrictedColumns,
        @Size(max = 200, message = "{validation.denied_columns.too_many}")
        List<@NotBlank(message = "{validation.denied_columns.item_blank}")
             @Pattern(regexp = DENIED_COLUMN_PATTERN,
                      message = "{validation.denied_columns.item_unqualified}") String> deniedColumns,
        Instant expiresAt
) {

    /** {@code table.column} or {@code schema.table.column}: two or three non-blank parts (#935). */
    static final String DENIED_COLUMN_PATTERN = "^[^.]*[^.\\s][^.]*(\\.[^.]*[^.\\s][^.]*){1,2}$";
}
