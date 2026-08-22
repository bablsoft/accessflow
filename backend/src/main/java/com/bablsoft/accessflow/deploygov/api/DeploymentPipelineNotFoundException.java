package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/** The deployment pipeline does not exist or belongs to another organization. */
public class DeploymentPipelineNotFoundException extends DeploymentGovernanceException {

    public DeploymentPipelineNotFoundException(UUID pipelineId) {
        super("Deployment pipeline not found: " + pipelineId);
    }
}
