package com.bablsoft.accessflow.ai.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AnalyzeQueryRequest(
        @NotNull(message = "{validation.datasource_id.required}") UUID datasourceId,
        @NotBlank(message = "{validation.sql.required}")
        @Size(max = 100_000, message = "{validation.sql.max}") String sql) {
}
