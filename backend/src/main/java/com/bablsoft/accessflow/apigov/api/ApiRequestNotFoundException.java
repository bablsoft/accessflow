package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiRequestNotFoundException extends ApiGovException {

    public ApiRequestNotFoundException(UUID apiRequestId) {
        super("API request not found: " + apiRequestId);
    }
}
