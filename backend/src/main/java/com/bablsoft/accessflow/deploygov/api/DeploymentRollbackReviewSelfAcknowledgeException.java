package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * The deployment's submitter tried to acknowledge their own rollback review (#693) — forbidden for
 * everyone, mirroring the break-glass "never the submitter" rule.
 */
public class DeploymentRollbackReviewSelfAcknowledgeException extends DeploymentGovernanceException {

    public DeploymentRollbackReviewSelfAcknowledgeException(UUID id) {
        super("The submitter cannot acknowledge their own rollback review: " + id);
    }
}
