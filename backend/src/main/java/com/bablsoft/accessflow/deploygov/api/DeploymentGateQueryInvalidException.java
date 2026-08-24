package com.bablsoft.accessflow.deploygov.api;

/**
 * The gate was called with an invalid parameter combination (#693): it takes either
 * {@code request_id} alone, or all three of {@code pipeline}, {@code version} and
 * {@code environment}.
 */
public class DeploymentGateQueryInvalidException extends DeploymentGovernanceException {

    public DeploymentGateQueryInvalidException() {
        super("Pass either request_id, or all of pipeline, version and environment");
    }
}
