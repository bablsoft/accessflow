package com.bablsoft.accessflow.core.api;

public sealed class UserProfileException extends RuntimeException
        permits PasswordIncorrectException, PasswordChangeNotAllowedException,
                TotpNotEnabledException, TotpAlreadyEnabledException, TotpInvalidCodeException {

    protected UserProfileException(String message) {
        super(message);
    }
}
