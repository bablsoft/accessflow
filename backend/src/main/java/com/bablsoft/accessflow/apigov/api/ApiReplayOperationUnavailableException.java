package com.bablsoft.accessflow.apigov.api;

/** Thrown when a replayed request's operation is no longer in the connector's filtered catalog (#886). */
public class ApiReplayOperationUnavailableException extends ApiGovException {

    private final String operationId;

    public ApiReplayOperationUnavailableException(String operationId) {
        super("Operation no longer available for replay: " + operationId);
        this.operationId = operationId;
    }

    public String operationId() {
        return operationId;
    }
}
