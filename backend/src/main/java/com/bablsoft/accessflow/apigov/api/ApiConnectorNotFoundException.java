package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiConnectorNotFoundException extends ApiGovException {

    public ApiConnectorNotFoundException(UUID connectorId) {
        super("API connector not found: " + connectorId);
    }
}
