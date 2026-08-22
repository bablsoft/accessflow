package com.bablsoft.accessflow.deploygov.api;

/** The organization already has a deployment pipeline with the requested name. */
public class DuplicateDeploymentPipelineNameException extends DeploymentGovernanceException {

    public DuplicateDeploymentPipelineNameException(String name) {
        super("A deployment pipeline named '" + name + "' already exists in this organization");
    }
}
