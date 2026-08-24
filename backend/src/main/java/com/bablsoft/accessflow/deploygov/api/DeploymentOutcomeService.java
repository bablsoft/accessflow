package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * Post-execution outcome reporting (#693). Idempotent: repeating the same outcome returns the
 * request unchanged; a different outcome conflicts. A {@code FAILED} outcome also flips the
 * request {@code EXECUTED → FAILED}, and a {@code ROLLED_BACK} outcome on an environment with
 * {@code require_review = true} opens a {@link DeploymentRollbackReviewService rollback
 * follow-up review} in the same transaction.
 */
public interface DeploymentOutcomeService {

    /**
     * Record what the pipeline reported after execution.
     *
     * @throws DeploymentRequestNotFoundException     unknown or cross-org id
     * @throws DeploymentRequestPermissionException   the caller is neither the submitter, a
     *                                                {@code can_trigger} holder, nor an admin
     * @throws IllegalDeploymentRequestStateException the request has not been executed
     * @throws DeploymentOutcomeConflictException     a different outcome was already reported
     */
    DeploymentRequestView reportOutcome(UUID requestId, DeploymentOutcome outcome, String detail,
                                        UUID organizationId, UUID callerId,
                                        Set<Permission> callerPermissions, String ipAddress);
}
