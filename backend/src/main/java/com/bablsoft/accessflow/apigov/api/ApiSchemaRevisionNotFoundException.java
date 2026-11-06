package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiSchemaRevisionNotFoundException extends ApiGovException {

    public ApiSchemaRevisionNotFoundException(UUID revisionId) {
        super("API schema revision not found: " + revisionId);
    }
}
