package com.bablsoft.accessflow.core.api;

public class InactiveUserException extends RuntimeException {

    private final String email;

    public InactiveUserException(String email) {
        super("Account for " + email + " is inactive");
        this.email = email;
    }

    public String email() {
        return email;
    }
}
