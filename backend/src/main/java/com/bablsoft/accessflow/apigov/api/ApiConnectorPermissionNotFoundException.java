package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiConnectorPermissionNotFoundException extends ApiGovException {

    public ApiConnectorPermissionNotFoundException(UUID permissionId) {
        super("API connector permission not found: " + permissionId);
    }
}
