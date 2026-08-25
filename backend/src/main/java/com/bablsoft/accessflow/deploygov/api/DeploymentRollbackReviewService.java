package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * The rollback follow-up worklist (#693): a {@code ROLLED_BACK} outcome on an environment with
 * {@code require_review = true} opens a record here that a reviewer — never the deployment's
 * submitter — must acknowledge. The deployment-side mirror of the break-glass retro-review.
 */
public interface DeploymentRollbackReviewService {

    /** List the organization's rollback reviews, newest first; {@code status} null = all. */
    PageResponse<DeploymentRollbackReviewView> list(UUID organizationId,
                                                    DeploymentRollbackReviewStatus status,
                                                    PageRequest pageRequest);

    /**
     * @throws DeploymentRollbackReviewNotFoundException unknown or cross-org id
     */
    DeploymentRollbackReviewView get(UUID id, UUID organizationId);

    /**
     * Acknowledge the rollback. Acknowledging an already-{@code REVIEWED} record is an idempotent
     * no-op returning the current state — acknowledgment is a latch, not a contested decision.
     *
     * @throws DeploymentRollbackReviewNotFoundException        unknown or cross-org id
     * @throws DeploymentRollbackReviewSelfAcknowledgeException the caller submitted the deployment
     */
    DeploymentRollbackReviewView acknowledge(UUID id, UUID organizationId, UUID reviewerId,
                                             String comment);
}
