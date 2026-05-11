package com.partqam.accessflow.core.api;

public final class PasswordIncorrectException extends UserProfileException {

    public PasswordIncorrectException(String message) {
        super(message);
    }
}
