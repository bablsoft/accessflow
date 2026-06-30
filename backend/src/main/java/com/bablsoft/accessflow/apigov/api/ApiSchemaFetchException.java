package com.bablsoft.accessflow.apigov.api;

/** Raised when a schema document cannot be fetched from its {@code sourceUrl}. */
public class ApiSchemaFetchException extends ApiGovException {

    public ApiSchemaFetchException(String message) {
        super(message);
    }
}
