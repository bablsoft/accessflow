package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/** The environment does not exist or belongs to a different pipeline. */
public class DeploymentEnvironmentNotFoundException extends DeploymentGovernanceException {

    public DeploymentEnvironmentNotFoundException(UUID environmentId) {
        super("Deployment environment not found: " + environmentId);
    }

    /** A trigger names its environment rather than identifying it (#691). */
    public DeploymentEnvironmentNotFoundException(UUID pipelineId, String environmentName) {
        super("Deployment environment not found on pipeline " + pipelineId + ": " + environmentName);
    }
}
