package com.bablsoft.accessflow.core.api;

public final class RoleNameAlreadyExistsException extends RoleAdminException {

    public RoleNameAlreadyExistsException(String name) {
        super("A role with this name already exists: " + name);
    }
}
