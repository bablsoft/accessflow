package com.bablsoft.accessflow.scim.api;

public final class ScimInvalidMappingException extends ScimException {

    public ScimInvalidMappingException(String attribute, String value) {
        super("Invalid SCIM attribute mapping: " + attribute + " = " + value);
    }
}
