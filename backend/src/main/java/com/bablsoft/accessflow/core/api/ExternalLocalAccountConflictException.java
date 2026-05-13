package com.bablsoft.accessflow.core.api;

public class ExternalLocalAccountConflictException extends RuntimeException {

    private final String email;

    public ExternalLocalAccountConflictException(String email) {
        super("Email " + email + " is already bound to a LOCAL account");
        this.email = email;
    }

    public String email() {
        return email;
    }
}
