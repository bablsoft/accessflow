package com.bablsoft.accessflow.apigov.api;

/** A submitted call failed validation against the connector's schema (unknown operation, etc.). */
public class ApiRequestValidationException extends ApiGovException {

    public ApiRequestValidationException(String message) {
        super(message);
    }
}
