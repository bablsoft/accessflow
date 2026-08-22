package com.bablsoft.accessflow.deploygov.api;

/**
 * The freeze-window definition violates a shape or scope rule (mixed one-off and recurring
 * fields, inverted bounds, bad timezone or day numbers, or an environment scope without a
 * matching pipeline). The message is resolved through the message source at the throw site.
 */
public class IllegalDeploymentFreezeWindowException extends DeploymentGovernanceException {

    public IllegalDeploymentFreezeWindowException(String message) {
        super(message);
    }
}
