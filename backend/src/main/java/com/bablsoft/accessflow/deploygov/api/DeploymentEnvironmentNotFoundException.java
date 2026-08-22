package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/** The environment does not exist or belongs to a different pipeline. */
public class DeploymentEnvironmentNotFoundException extends DeploymentGovernanceException {

    public DeploymentEnvironmentNotFoundException(UUID environmentId) {
        super("Deployment environment not found: " + environmentId);
    }
}
