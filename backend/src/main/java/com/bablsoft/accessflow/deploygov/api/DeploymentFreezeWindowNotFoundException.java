package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/** The freeze window does not exist or belongs to another organization. */
public class DeploymentFreezeWindowNotFoundException extends DeploymentGovernanceException {

    public DeploymentFreezeWindowNotFoundException(UUID freezeWindowId) {
        super("Deployment freeze window not found: " + freezeWindowId);
    }
}
