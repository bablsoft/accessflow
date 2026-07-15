package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class SystemRoleImmutableException extends RoleAdminException {

    public SystemRoleImmutableException(UUID id) {
        super("System roles are immutable: " + id);
    }
}
