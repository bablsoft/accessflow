package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * The deployment request does not exist, belongs to another organization, or is not visible to the
 * caller. Deliberately also thrown instead of a permission error on the detail endpoint, so the
 * endpoint cannot be used to probe for ids.
 */
public class DeploymentRequestNotFoundException extends DeploymentGovernanceException {

    public DeploymentRequestNotFoundException(UUID deploymentRequestId) {
        super("Deployment request not found: " + deploymentRequestId);
    }
}
