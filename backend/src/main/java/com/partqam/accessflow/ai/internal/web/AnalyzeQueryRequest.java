package com.partqam.accessflow.ai.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AnalyzeQueryRequest(
        @NotNull UUID datasourceId,
        @NotBlank @Size(max = 100_000) String sql) {
}
