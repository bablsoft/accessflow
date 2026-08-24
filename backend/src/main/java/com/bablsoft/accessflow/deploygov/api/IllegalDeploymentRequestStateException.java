package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

/**
 * The deployment request is not in a state that allows the attempted action or transition. The
 * current status rides along so the web layer can surface it on the {@code ProblemDetail}.
 */
public class IllegalDeploymentRequestStateException extends DeploymentGovernanceException {

    private final transient QueryStatus currentStatus;

    public IllegalDeploymentRequestStateException(QueryStatus currentStatus, String message) {
        super(message);
        this.currentStatus = currentStatus;
    }

    public IllegalDeploymentRequestStateException(QueryStatus currentStatus, QueryStatus attempted) {
        this(currentStatus, "Illegal deployment request transition: " + currentStatus + " -> " + attempted);
    }

    public QueryStatus getCurrentStatus() {
        return currentStatus;
    }
}
