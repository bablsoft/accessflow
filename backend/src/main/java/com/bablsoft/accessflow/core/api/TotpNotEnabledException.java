package com.bablsoft.accessflow.core.api;

public final class TotpNotEnabledException extends UserProfileException {

    public TotpNotEnabledException(String message) {
        super(message);
    }
}
