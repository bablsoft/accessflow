package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiSchemaDriftFindingNotFoundException extends ApiGovException {

    public ApiSchemaDriftFindingNotFoundException(UUID findingId) {
        super("API schema drift finding not found: " + findingId);
    }
}
