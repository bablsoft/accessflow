package com.bablsoft.accessflow.scim.api;

/** Base of the scim module's admin-facing exception hierarchy (#621). */
public abstract class ScimException extends RuntimeException {

    protected ScimException(String message) {
        super(message);
    }
}
