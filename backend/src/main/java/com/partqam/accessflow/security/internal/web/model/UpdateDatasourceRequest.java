package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.SslMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateDatasourceRequest(
        @Size(min = 1, max = 255) String name,
        @Size(min = 1, max = 255) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(min = 1, max = 255) String databaseName,
        @Size(min = 1, max = 255) String username,
        @Size(min = 1, max = 4096) String password,
        SslMode sslMode,
        @Min(1) @Max(200) Integer connectionPoolSize,
        @Min(1) @Max(1_000_000) Integer maxRowsPerQuery,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        Boolean active
) {}
