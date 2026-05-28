package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the set of users eligible to review queries on a particular datasource
 * based on the per-datasource reviewer assignment table.
 * Falls back to plan-approver eligibility when no rows exist for the datasource.
 */
public interface ReviewerEligibilityService {

    /**
     * Returns the set of user ids that are explicitly scoped to review queries on
     * the given datasource (direct reviewers + members of group reviewers), or
     * {@link Optional#empty()} if no per-datasource assignment exists for that
     * datasource (caller should fall back to plan approvers).
     */
    Optional<Set<UUID>> findEligibleReviewerIds(UUID datasourceId);

    boolean hasDatasourceScopedReviewers(UUID datasourceId);
}
