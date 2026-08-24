package com.bablsoft.accessflow.deploygov.api;

/**
 * A break-glass deployment was refused (#692): the caller has no effective
 * {@code can_break_glass} grant on the pipeline, or the target environment does not allow
 * break-glass. Both gates apply to everyone — admins included.
 */
public class DeploymentBreakGlassNotAllowedException extends DeploymentGovernanceException {

    public DeploymentBreakGlassNotAllowedException(String message) {
        super(message);
    }
}
