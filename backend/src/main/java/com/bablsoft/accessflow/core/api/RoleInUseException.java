package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class RoleInUseException extends RoleAdminException {

    public RoleInUseException(UUID id, long assignedUsers) {
        super("Role is assigned to " + assignedUsers + " user(s) and cannot be deleted: " + id);
    }
}
