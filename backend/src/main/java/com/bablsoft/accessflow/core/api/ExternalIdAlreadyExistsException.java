package com.bablsoft.accessflow.core.api;

/** Another user in the organization already carries this SCIM externalId (#621). */
public final class ExternalIdAlreadyExistsException extends UserAdminException {

    public ExternalIdAlreadyExistsException(String externalId) {
        super("User already exists with external id: " + externalId);
    }
}
