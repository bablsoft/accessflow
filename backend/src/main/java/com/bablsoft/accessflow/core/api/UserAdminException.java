package com.bablsoft.accessflow.core.api;

public sealed class UserAdminException extends RuntimeException
        permits EmailAlreadyExistsException, UserNotFoundException, IllegalUserOperationException,
                SetupAlreadyCompletedException {

    protected UserAdminException(String message) {
        super(message);
    }
}
