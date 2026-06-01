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
}
