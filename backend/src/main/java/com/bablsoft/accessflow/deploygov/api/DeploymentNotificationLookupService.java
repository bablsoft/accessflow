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
     * The users to alert that the deployment awaits review, following the #692 plan-approver rule:
     * when the resolved review plan (the environment's override, else the pipeline's) carries
     * stage-1 approver rules, the users those rules name; otherwise the REVIEWER and ADMIN
     * <em>system-role</em> holders. The submitter is always excluded — they can never approve
     * their own deployment. Note the review guard itself is permission-based
     * ({@code DEPLOYMENT_REVIEW}), so a user holding it through a custom role can still decide
     * without appearing in this fallback — the same known narrowing as the apigov recipient set;
     * such users still see the event on the org-wide channel fanout.
     */
    List<UUID> findEligibleReviewerUserIds(UUID deploymentRequestId);

    /** The reviewers who granted the deployment — an APPROVED review decision on the request. */
    List<UUID> findApproverUserIds(UUID deploymentRequestId);
}
