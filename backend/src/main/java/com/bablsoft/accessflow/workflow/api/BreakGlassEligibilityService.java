package com.bablsoft.accessflow.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves which datasources a user may currently break-glass on (AF-385). Backs the editor's
 * "Emergency access" button gating via {@code GET /api/v1/me/break-glass}.
 */
public interface BreakGlassEligibilityService {

    List<BreakGlassEligibility> findEligible(UUID userId, UUID organizationId);
}
