package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only projections of a deployment request for the notifications module (#695), so it can
 * render deployment notifications and resolve their recipients without reaching into deploygov
 * internals. Active-user filtering is deliberately left to the caller — the id lists returned here
 * are membership facts, not delivery decisions.
 */
public interface DeploymentNotificationLookupService {

    Optional<DeploymentNotificationView> find(UUID deploymentRequestId);

    /**
     * The users eligible to review the deployment, mirroring the #692 plan-approver rule: when the
     * resolved review plan (the environment's override, else the pipeline's) carries stage-1
     * approver rules, only the users those rules name qualify; otherwise every holder of the
     * {@code DEPLOYMENT_REVIEW} permission via the REVIEWER or ADMIN system role does. The
     * submitter is always excluded — they can never approve their own deployment.
     */
    List<UUID> findEligibleReviewerUserIds(UUID deploymentRequestId);

    /** The reviewers who granted the deployment — an APPROVED review decision on the request. */
    List<UUID> findApproverUserIds(UUID deploymentRequestId);
}
