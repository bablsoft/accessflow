package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

/** Thrown when an API request transition is attempted from an incompatible current status. */
public class IllegalApiRequestStateException extends ApiGovException {

    private final transient QueryStatus currentStatus;

    public IllegalApiRequestStateException(QueryStatus currentStatus, String message) {
        super(message);
        this.currentStatus = currentStatus;
    }

    public QueryStatus currentStatus() {
        return currentStatus;
    }
}
