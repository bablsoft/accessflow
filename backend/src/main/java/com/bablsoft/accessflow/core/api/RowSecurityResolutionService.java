package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the row-security policies that <em>apply</em> to one query execution. A policy applies
 * when the submitter is in its {@code applies_to} scope: either the scope is empty (applies to
 * every submitter — the governance-safe default) or the submitter's role / group / user id matches
 * one of the listed targets. There is no implicit ADMIN bypass. Disabled policies are ignored.
 *
 * <p>Each applicable policy's value source is resolved to concrete bound value(s) from the
 * submitter's built-in attributes ({@code user.id} / {@code user.email} / {@code user.role} /
 * {@code user.groups}) or their admin-set {@code users.attributes} map. A variable that cannot be
 * resolved yields an empty {@code values} list — a fail-closed deny, never a skipped predicate.
 *
 * <p>Returns an empty list when no enabled policy covers the datasource or none target the submitter.
 */
public interface RowSecurityResolutionService {

    List<ResolvedRowSecurityPredicate> resolveApplicable(UUID organizationId, UUID datasourceId,
                                                         UUID requesterUserId);

    /**
     * Same resolution, but over the policy set the datasource <em>would</em> have if {@code draft}
     * were saved: the draft replaces {@link RowSecurityPolicyDraft#replacesPolicyId()} when that is
     * set, and is added otherwise. Used by the policy simulator (issue AF-630) to compute the
     * "simulated" arm of its A/B; the draft is never persisted.
     *
     * <p>A disabled draft resolves to the persisted set minus the policy it replaces — which is
     * exactly what saving it would mean.
     */
    List<ResolvedRowSecurityPredicate> resolveWithDraft(UUID organizationId, UUID datasourceId,
                                                        UUID requesterUserId,
                                                        RowSecurityPolicyDraft draft);
}
