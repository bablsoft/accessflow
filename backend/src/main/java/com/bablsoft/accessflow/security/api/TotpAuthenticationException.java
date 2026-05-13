package com.bablsoft.accessflow.security.api;

public class TotpAuthenticationException extends RuntimeException {

    public TotpAuthenticationException(String message) {
        super(message);
    }
}
