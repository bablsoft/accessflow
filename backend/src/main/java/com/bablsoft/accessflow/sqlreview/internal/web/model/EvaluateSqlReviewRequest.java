package com.bablsoft.accessflow.sqlreview.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** The editor's live-lint request — the same shape as {@code POST /queries/analyze}. */
public record EvaluateSqlReviewRequest(
        @NotNull(message = "{validation.datasource_id.required}") UUID datasourceId,
        @NotBlank(message = "{validation.sql.required}")
        @Size(max = 100_000, message = "{validation.sql.max}") String sql
) {
}
