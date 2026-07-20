package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiConnectorVariableNotFoundException extends ApiGovException {

    public ApiConnectorVariableNotFoundException(UUID variableId) {
        super("API connector variable not found: " + variableId);
    }
}
