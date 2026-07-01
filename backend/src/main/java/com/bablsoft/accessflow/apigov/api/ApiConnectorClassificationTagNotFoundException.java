package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiConnectorClassificationTagNotFoundException extends ApiGovException {

    public ApiConnectorClassificationTagNotFoundException(UUID tagId) {
        super("API connector classification tag not found: " + tagId);
    }
}
