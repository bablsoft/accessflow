package com.bablsoft.accessflow.core.api;

public final class SetupAlreadyCompletedException extends UserAdminException {

    public SetupAlreadyCompletedException() {
        super("Setup has already been completed");
    }
}
