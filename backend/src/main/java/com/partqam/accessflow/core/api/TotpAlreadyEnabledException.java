package com.partqam.accessflow.core.api;

public final class TotpAlreadyEnabledException extends UserProfileException {

    public TotpAlreadyEnabledException(String message) {
        super(message);
    }
}
