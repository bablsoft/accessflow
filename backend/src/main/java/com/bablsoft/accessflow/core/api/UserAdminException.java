package com.bablsoft.accessflow.core.api;

public sealed class UserAdminException extends RuntimeException
        permits EmailAlreadyExistsException, ExternalIdAlreadyExistsException,
                UserNotFoundException, IllegalUserOperationException,
                SetupAlreadyCompletedException {

    protected UserAdminException(String message) {
        super(message);
    }
}
