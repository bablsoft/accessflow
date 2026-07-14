package com.bablsoft.accessflow.core.api;

public sealed class RoleAdminException extends RuntimeException
        permits RoleNotFoundException,
                RoleNameAlreadyExistsException,
                SystemRoleImmutableException,
                RoleInUseException {

    protected RoleAdminException(String message) {
        super(message);
    }
}
