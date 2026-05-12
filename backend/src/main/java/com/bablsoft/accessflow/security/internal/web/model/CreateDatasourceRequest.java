package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateDatasourceRequest(
        @NotBlank(message = "{validation.datasource_name.required}")
        @Size(max = 255, message = "{validation.display_name.max}") String name,
        @NotNull(message = "{validation.db_type.required}") DbType dbType,
        @Size(max = 255, message = "{validation.display_name.max}") String host,
        @Min(value = 1, message = "{validation.port.range}")
        @Max(value = 65535, message = "{validation.port.range}") Integer port,
        @Size(max = 255, message = "{validation.display_name.max}") String databaseName,
        @NotBlank(message = "{validation.username.required}")
        @Size(max = 255, message = "{validation.display_name.max}") String username,
        @NotBlank(message = "{validation.datasource_password.required}")
        @Size(max = 4096) String password,
        @NotNull(message = "{validation.ssl_mode.required}") SslMode sslMode,
        @Min(value = 1, message = "{validation.pool_size.range}")
        @Max(value = 200, message = "{validation.pool_size.range}") Integer connectionPoolSize,
        @Min(value = 1, message = "{validation.max_rows.range}")
        @Max(value = 1_000_000, message = "{validation.max_rows.range}") Integer maxRowsPerQuery,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        UUID customDriverId,
        @Size(max = 2048, message = "{validation.jdbc_url.length}")
        @Pattern(regexp = "^jdbc:[a-zA-Z][a-zA-Z0-9+\\-.]*:.+$",
                message = "{validation.jdbc_url.format}") String jdbcUrlOverride
) {}
