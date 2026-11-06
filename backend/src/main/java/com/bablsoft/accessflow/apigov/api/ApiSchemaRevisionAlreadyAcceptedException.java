package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiSchemaRevisionAlreadyAcceptedException extends ApiGovException {

    public ApiSchemaRevisionAlreadyAcceptedException(UUID revisionId) {
        super("API schema revision already accepted: " + revisionId);
    }
}
