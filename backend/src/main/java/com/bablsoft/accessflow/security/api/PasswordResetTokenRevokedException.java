package com.bablsoft.accessflow.security.api;

public class PasswordResetTokenRevokedException extends RuntimeException {

    public PasswordResetTokenRevokedException() {
        super("Password reset token has been revoked");
    }
}
