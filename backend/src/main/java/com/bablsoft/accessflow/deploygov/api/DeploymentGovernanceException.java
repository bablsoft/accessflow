package com.bablsoft.accessflow.deploygov.api;

/** Base type for deployment-governance domain exceptions. */
public abstract class DeploymentGovernanceException extends RuntimeException {

    protected DeploymentGovernanceException(String message) {
        super(message);
    }
}
