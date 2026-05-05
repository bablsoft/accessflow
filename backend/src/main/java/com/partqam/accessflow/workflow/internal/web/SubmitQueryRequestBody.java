package com.partqam.accessflow.workflow.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SubmitQueryRequestBody(
        @NotNull UUID datasourceId,
        @NotBlank @Size(max = 100_000) String sql,
        @Size(max = 4000) String justification) {
}
