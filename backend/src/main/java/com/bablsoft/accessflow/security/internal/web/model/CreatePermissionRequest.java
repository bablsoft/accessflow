package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DeniedTables;
import com.bablsoft.accessflow.core.api.QueryShape;
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
        // #941: bytes-scanned cap for this grant; only accepted on bytes-reporting engines.
        @Min(value = 1, message = "{validation.bytes_cap.min}") Long bytesScannedLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<@NotBlank(message = "{validation.restricted_columns.item_blank}") String> restrictedColumns,
        @Size(max = 200, message = "{validation.denied_columns.too_many}")
        List<@NotBlank(message = "{validation.denied_columns.item_blank}")
             @Pattern(regexp = DENIED_COLUMN_PATTERN,
                      message = "{validation.denied_columns.item_unqualified}") String> deniedColumns,
        @Size(max = 50, message = "{validation.denied_schemas.too_many}")
        List<@NotBlank(message = "{validation.denied_schemas.item_blank}")
             @Pattern(regexp = DeniedTables.SCHEMA_ENTRY_PATTERN,
                      message = "{validation.denied_schemas.item_invalid}") String> deniedSchemas,
        @Size(max = 200, message = "{validation.denied_tables.too_many}")
        List<@NotBlank(message = "{validation.denied_tables.item_blank}")
             @Pattern(regexp = DeniedTables.TABLE_ENTRY_PATTERN,
                      message = "{validation.denied_tables.item_invalid}") String> deniedTables,
        @Size(max = 8, message = "{validation.denied_shapes.too_many}")
        List<@NotNull(message = "{validation.denied_shapes.item_blank}") QueryShape> deniedShapes,
        Instant expiresAt
) {

    /** {@code table.column} or {@code schema.table.column}: two or three non-blank parts (#935). */
    static final String DENIED_COLUMN_PATTERN = "^[^.]*[^.\\s][^.]*(\\.[^.]*[^.\\s][^.]*){1,2}$";
}
