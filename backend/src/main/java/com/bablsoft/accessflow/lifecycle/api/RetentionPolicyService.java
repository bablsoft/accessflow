package com.bablsoft.accessflow.lifecycle.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Admin-facing CRUD + dry-run for retention policies. All operations are organization-scoped; the
 * web layer enforces the ADMIN role. Business-invariant validation throws
 * {@link InvalidRetentionPolicyException}; a missing/foreign policy throws
 * {@link RetentionPolicyNotFoundException}.
 */
public interface RetentionPolicyService {

    RetentionPolicyView create(CreateRetentionPolicyCommand command);

    RetentionPolicyView update(UpdateRetentionPolicyCommand command);

    RetentionPolicyView get(UUID policyId, UUID organizationId);

    PageResponse<RetentionPolicyView> list(UUID organizationId, PageRequest pageRequest);

    void delete(UUID policyId, UUID organizationId);

    /** Computes the dry-run impact of a policy without executing it. */
    LifecyclePreviewResult preview(UUID policyId, UUID organizationId);
}
