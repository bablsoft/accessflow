package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.SslMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateDatasourceRequest(
        @Size(min = 1, max = 255, message = "{validation.datasource_name.required}") String name,
        @Size(min = 1, max = 255, message = "{validation.host.required}") String host,
        @Min(value = 1, message = "{validation.port.range}")
        @Max(value = 65535, message = "{validation.port.range}") Integer port,
        @Size(min = 1, max = 255, message = "{validation.database_name.required}") String databaseName,
        @Size(min = 1, max = 255, message = "{validation.username.required}") String username,
        @Size(min = 1, max = 4096) String password,
        SslMode sslMode,
        @Min(value = 1, message = "{validation.pool_size.range}")
        @Max(value = 200, message = "{validation.pool_size.range}") Integer connectionPoolSize,
        @Min(value = 1, message = "{validation.max_rows.range}")
        @Max(value = 1_000_000, message = "{validation.max_rows.range}") Integer maxRowsPerQuery,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean clearAiConfig,
        Boolean active
) {}
