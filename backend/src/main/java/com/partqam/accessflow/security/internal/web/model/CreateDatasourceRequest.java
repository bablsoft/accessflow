package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SslMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateDatasourceRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull DbType dbType,
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank @Size(max = 255) String databaseName,
        @NotBlank @Size(max = 255) String username,
        @NotBlank @Size(max = 4096) String password,
        @NotNull SslMode sslMode,
        @Min(1) @Max(200) Integer connectionPoolSize,
        @Min(1) @Max(1_000_000) Integer maxRowsPerQuery,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled
) {}
