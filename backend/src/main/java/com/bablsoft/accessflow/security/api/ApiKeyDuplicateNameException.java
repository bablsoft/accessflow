package com.bablsoft.accessflow.security.api;

public class ApiKeyDuplicateNameException extends RuntimeException {

    private final String name;

    public ApiKeyDuplicateNameException(String name) {
        super("API key with name already exists: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
