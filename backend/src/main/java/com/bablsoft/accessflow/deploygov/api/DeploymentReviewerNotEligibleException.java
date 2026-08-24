package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * The caller holds {@code DEPLOYMENT_REVIEW} but matches none of the resolved review plan's
 * stage-1 approver rules for this deployment request (#692).
 */
public class DeploymentReviewerNotEligibleException extends DeploymentGovernanceException {

    public DeploymentReviewerNotEligibleException(UUID reviewerId, UUID deploymentRequestId) {
        super("User " + reviewerId + " is not an eligible approver for deployment request "
                + deploymentRequestId);
    }
}
