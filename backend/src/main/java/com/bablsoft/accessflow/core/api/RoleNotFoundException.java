package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class RoleNotFoundException extends RoleAdminException {

    public RoleNotFoundException(UUID id) {
        super("Role not found: " + id);
    }
}
