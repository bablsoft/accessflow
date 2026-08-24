package com.bablsoft.accessflow.deploygov.api;

/**
 * The routing policy definition is invalid — an unknown timezone, a day number outside 1–7, equal
 * start and end times, or a {@code requiredApprovals} that does not match the chosen action. The
 * message is resolved through {@code MessageSource} at the throw site.
 */
public class IllegalDeploymentRoutingPolicyException extends DeploymentGovernanceException {

    public IllegalDeploymentRoutingPolicyException(String message) {
        super(message);
    }
}
