package com.partqam.accessflow.core.api;

public final class PasswordChangeNotAllowedException extends UserProfileException {

    public PasswordChangeNotAllowedException(String message) {
        super(message);
    }
}
