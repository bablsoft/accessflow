package com.partqam.accessflow.core.api;

public final class EmailAlreadyExistsException extends UserAdminException {

    public EmailAlreadyExistsException(String email) {
        super("User already exists with email: " + email);
    }
}
