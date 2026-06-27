package com.bablsoft.accessflow.apigov.api;

/** Raised when executing an approved API call against the upstream target fails. */
public class ApiExecutionException extends ApiGovException {

    public ApiExecutionException(String message) {
        super(message);
    }
}
