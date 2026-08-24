package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/** The routing policy does not exist or belongs to another organization. */
public class DeploymentRoutingPolicyNotFoundException extends DeploymentGovernanceException {

    public DeploymentRoutingPolicyNotFoundException(UUID policyId) {
        super("Deployment routing policy not found: " + policyId);
    }
}
