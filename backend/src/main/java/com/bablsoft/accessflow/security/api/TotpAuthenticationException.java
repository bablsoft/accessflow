package com.bablsoft.accessflow.security.api;

import org.springframework.security.core.AuthenticationException;

public class TotpAuthenticationException extends AuthenticationException {

    public TotpAuthenticationException(String message) {
        super(message);
    }
}
