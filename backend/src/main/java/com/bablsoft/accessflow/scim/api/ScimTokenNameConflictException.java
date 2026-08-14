package com.bablsoft.accessflow.scim.api;

public final class ScimTokenNameConflictException extends ScimException {

    public ScimTokenNameConflictException(String name) {
        super("A SCIM token with this name already exists: " + name);
    }
}
