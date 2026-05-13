package com.bablsoft.accessflow.security.api;

public class TotpRequiredException extends RuntimeException {

    public TotpRequiredException(String message) {
        super(message);
    }
}
