package com.bablsoft.accessflow.apigov.api;

/** Base type for API-governance domain exceptions. */
public abstract class ApiGovException extends RuntimeException {

    protected ApiGovException(String message) {
        super(message);
    }
}
