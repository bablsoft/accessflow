package com.bablsoft.accessflow.core.api;

public final class TotpAlreadyEnabledException extends UserProfileException {

    public TotpAlreadyEnabledException(String message) {
        super(message);
    }
}
