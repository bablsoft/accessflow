package com.bablsoft.accessflow.ai.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record GenerateSqlRequest(
        @NotNull(message = "{validation.datasource_id.required}") UUID datasourceId,
        @NotBlank(message = "{validation.text_to_sql_prompt.required}")
        @Size(max = 4000, message = "{validation.text_to_sql_prompt.max}") String prompt) {
}
