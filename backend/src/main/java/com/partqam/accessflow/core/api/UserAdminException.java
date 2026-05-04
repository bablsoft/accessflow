package com.partqam.accessflow.core.api;

public sealed class UserAdminException extends RuntimeException
        permits EmailAlreadyExistsException, UserNotFoundException, IllegalUserOperationException {

    protected UserAdminException(String message) {
        super(message);
    }
}
