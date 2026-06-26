package com.bablsoft.accessflow.apigov.api;

public class DuplicateApiConnectorNameException extends ApiGovException {

    public DuplicateApiConnectorNameException(String name) {
        super("An API connector named '" + name + "' already exists in this organization");
    }
}
