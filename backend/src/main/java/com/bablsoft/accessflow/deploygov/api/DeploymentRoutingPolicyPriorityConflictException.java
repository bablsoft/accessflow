package com.bablsoft.accessflow.deploygov.api;

/** Another routing policy in the organization already uses the requested {@code priority}. */
public class DeploymentRoutingPolicyPriorityConflictException extends DeploymentGovernanceException {

    public DeploymentRoutingPolicyPriorityConflictException(int priority) {
        super("Deployment routing policy priority already in use: " + priority);
    }
}
