package com.bablsoft.accessflow.core.api;

public final class TotpInvalidCodeException extends UserProfileException {

    public TotpInvalidCodeException(String message) {
        super(message);
    }
}
