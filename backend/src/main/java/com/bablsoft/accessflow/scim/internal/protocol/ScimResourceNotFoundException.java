package com.bablsoft.accessflow.scim.internal.protocol;

public final class ScimResourceNotFoundException extends ScimProtocolException {

    public ScimResourceNotFoundException(String resourceType, String id) {
        super(404, null, resourceType + " " + id + " not found");
    }
}
