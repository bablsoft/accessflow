package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/** The rollback follow-up review does not exist or belongs to another organization (#693). */
public class DeploymentRollbackReviewNotFoundException extends DeploymentGovernanceException {

    public DeploymentRollbackReviewNotFoundException(UUID id) {
        super("Deployment rollback review not found: " + id);
    }
}
