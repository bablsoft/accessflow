package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class OrganizationNotFoundException extends OrganizationAdminException {

    public OrganizationNotFoundException(UUID id) {
        super("Organization not found: " + id);
    }
}
