package com.bablsoft.accessflow.sqlreview.api;

import java.util.UUID;

/**
 * Deterministic SQL review evaluation (#861, epic #860; implemented in #862, user-scoped entry point in #863). Resolves the ruleset
 * for the datasource — its {@code environment}, else the organization-wide default, else no
 * rules — and evaluates every enabled rule against the parsed statements.
 */
public interface SqlReviewService {

    /**
     * Evaluates {@code sql} against the ruleset resolved for the datasource. Returns
     * {@link SqlReviewResult#notApplicable()} for an engine the rule catalog does not cover, and an
     * applicable result with zero findings when no ruleset is bound. Never throws on a rule
     * evaluation failure — an unevaluable rule is skipped, not reported as a finding.
     */
    SqlReviewResult evaluate(UUID organizationId, UUID datasourceId, String sql);

    /**
     * {@link #evaluate} on behalf of a signed-in user (#863): the datasource is resolved through the
     * caller's visibility — a direct or group permission, or {@code isAdmin} ({@code QUERY_ADMIN}) —
     * exactly as {@code POST /queries/dry-run} does, so a datasource the user cannot see raises
     * {@code DatasourceNotFoundException} and never reveals its ruleset. Read-only: nothing is
     * persisted, audited or published.
     */
    SqlReviewResult evaluateForUser(UUID organizationId, UUID userId, boolean isAdmin, UUID datasourceId,
                                    String sql);
}
