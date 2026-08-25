package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * A different outcome has already been reported for this deployment request (#693) — repeating the
 * <em>same</em> outcome is an idempotent no-op, only a conflicting one lands here.
 */
public class DeploymentOutcomeConflictException extends DeploymentGovernanceException {

    public DeploymentOutcomeConflictException(UUID deploymentRequestId, DeploymentOutcome existing,
                                              DeploymentOutcome attempted) {
        super("Deployment outcome conflict for " + deploymentRequestId + ": " + existing
                + " already reported, attempted " + attempted);
    }
}
