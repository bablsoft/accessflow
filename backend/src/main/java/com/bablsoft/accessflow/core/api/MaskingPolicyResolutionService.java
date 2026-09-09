package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the masking policies that <em>apply</em> to one query execution. A policy applies when
 * the requester is <em>not</em> revealed by it — i.e. the requester's role, none of their group
 * ids, and their user id are absent from the policy's reveal lists. Disabled policies are ignored.
 *
 * <p>Returns an empty list when no enabled policy covers the datasource or when every covering
 * policy reveals the requester.
 */
public interface MaskingPolicyResolutionService {

    List<ResolvedColumnMask> resolveApplicable(UUID organizationId, UUID datasourceId,
                                               UUID requesterUserId);

    /**
     * Same resolution, but over the policy set the datasource <em>would</em> have if {@code draft}
     * were saved: the draft replaces {@link MaskingPolicyDraft#replacesPolicyId()} when that is set,
     * and is added otherwise. Used by the policy simulator (issue AF-630) to compute the "simulated"
     * arm of its A/B; the draft is never persisted.
     */
    List<ResolvedColumnMask> resolveWithDraft(UUID organizationId, UUID datasourceId,
                                              UUID requesterUserId, MaskingPolicyDraft draft);
}
