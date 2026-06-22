package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.BreakGlassEligibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The datasources the caller may currently break-glass on (AF-385). Backs the editor's
 * "Emergency access" button gating.
 */
public record BreakGlassEligibilityResponse(List<EligibleDatasource> eligibleDatasources) {

    public record EligibleDatasource(UUID datasourceId, Instant expiresAt) {
    }

    public static BreakGlassEligibilityResponse from(List<BreakGlassEligibility> eligible) {
        return new BreakGlassEligibilityResponse(eligible.stream()
                .map(e -> new EligibleDatasource(e.datasourceId(), e.expiresAt()))
                .toList());
    }
}
