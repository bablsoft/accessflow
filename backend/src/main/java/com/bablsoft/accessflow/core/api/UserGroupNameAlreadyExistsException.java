package com.bablsoft.accessflow.core.api;

public class UserGroupNameAlreadyExistsException extends RuntimeException {

    private final String name;

    public UserGroupNameAlreadyExistsException(String name) {
        super("User group with name '" + name + "' already exists in this organization");
        this.name = name;
    }

    public String name() {
        return name;
    }
}
