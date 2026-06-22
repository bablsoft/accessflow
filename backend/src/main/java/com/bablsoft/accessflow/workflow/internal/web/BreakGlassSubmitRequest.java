package com.bablsoft.accessflow.workflow.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Break-glass submit-and-execute request (AF-385). Unlike a normal submission, the justification is
 * mandatory — the emergency bypass demands a recorded reason.
 */
public record BreakGlassSubmitRequest(
        @NotNull(message = "{validation.datasource_id.required}") UUID datasourceId,
        @NotBlank(message = "{validation.sql.required}")
        @Size(max = 100_000, message = "{validation.sql.max}") String sql,
        @NotBlank(message = "{validation.break_glass.justification_required}")
        @Size(max = 4000, message = "{validation.justification.max}") String justification) {
}
