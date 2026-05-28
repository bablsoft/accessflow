package com.bablsoft.accessflow.workflow.internal.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Response body for {@code GET /queries/{id}/diff} (AF-361).
 *
 * <p>Each delta is {@code current - previous} — positive means the new run returned more
 * rows / took longer; negative means fewer / faster. {@code rowCountDelta} is only
 * populated when both runs are {@code SELECT} and both have a persisted result snapshot;
 * otherwise it is {@code null} so the frontend can hide the row.
 *
 * <p>{@code @JsonInclude(ALWAYS)} overrides the global {@code non_null} default so the
 * frontend always sees the three delta keys (with {@code null} when not applicable)
 * rather than having to defensively check for missing properties.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record QueryDiffResponse(
        UUID currentRunId,
        UUID previousRunId,
        Long rowsAffectedDelta,
        Integer executionMsDelta,
        Long rowCountDelta) {
}
