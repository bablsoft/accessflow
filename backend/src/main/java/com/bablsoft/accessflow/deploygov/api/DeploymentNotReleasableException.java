package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Confirm-execution was attempted on an {@code APPROVED} request that is not currently releasable
 * — an active freeze window, or a {@code scheduled_for} still in the future (#693). The current
 * status rides along for the {@code ProblemDetail}.
 */
public class DeploymentNotReleasableException extends DeploymentGovernanceException {

    private final transient QueryStatus currentStatus;

    public DeploymentNotReleasableException(UUID deploymentRequestId, QueryStatus currentStatus) {
        super("Deployment request is not releasable: " + deploymentRequestId);
        this.currentStatus = currentStatus;
    }

    public QueryStatus getCurrentStatus() {
        return currentStatus;
    }
}
