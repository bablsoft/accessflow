package com.bablsoft.accessflow.security.api;

import org.springframework.security.core.AuthenticationException;

public class TotpRequiredException extends AuthenticationException {

    public TotpRequiredException(String message) {
        super(message);
    }
}
