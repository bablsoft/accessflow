package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiSchemaNotFoundException extends ApiGovException {

    public ApiSchemaNotFoundException(UUID schemaId) {
        super("API schema not found: " + schemaId);
    }
}
