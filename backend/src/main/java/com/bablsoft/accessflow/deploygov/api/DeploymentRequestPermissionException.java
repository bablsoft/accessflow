package com.bablsoft.accessflow.deploygov.api;

/**
 * The caller may not perform this action on the deployment request — no {@code can_trigger} grant
 * on the pipeline, or not the submitter of the request being cancelled.
 */
public class DeploymentRequestPermissionException extends DeploymentGovernanceException {

    public DeploymentRequestPermissionException(String message) {
        super(message);
    }
}
