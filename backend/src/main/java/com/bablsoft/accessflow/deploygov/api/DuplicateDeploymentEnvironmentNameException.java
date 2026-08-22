package com.bablsoft.accessflow.deploygov.api;

/** The pipeline already has an environment with the requested name. */
public class DuplicateDeploymentEnvironmentNameException extends DeploymentGovernanceException {

    public DuplicateDeploymentEnvironmentNameException(String name) {
        super("An environment named '" + name + "' already exists on this pipeline");
    }
}
