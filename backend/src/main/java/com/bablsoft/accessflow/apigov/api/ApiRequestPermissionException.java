package com.bablsoft.accessflow.apigov.api;

/** The caller lacks the required permission (read/write/break-glass) on the connector. */
public class ApiRequestPermissionException extends ApiGovException {

    public ApiRequestPermissionException(String message) {
        super(message);
    }
}
