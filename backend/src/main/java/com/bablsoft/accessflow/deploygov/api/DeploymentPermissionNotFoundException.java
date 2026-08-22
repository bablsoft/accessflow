package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/** The pipeline permission grant does not exist or belongs to a different pipeline. */
public class DeploymentPermissionNotFoundException extends DeploymentGovernanceException {

    public DeploymentPermissionNotFoundException(UUID permissionId) {
        super("Deployment pipeline permission not found: " + permissionId);
    }
}
