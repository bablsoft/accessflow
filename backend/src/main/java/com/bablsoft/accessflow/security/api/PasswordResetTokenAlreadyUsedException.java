package com.bablsoft.accessflow.security.api;

public class PasswordResetTokenAlreadyUsedException extends RuntimeException {

    public PasswordResetTokenAlreadyUsedException() {
        super("Password reset token has already been used");
    }
}
