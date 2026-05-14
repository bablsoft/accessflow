package com.bablsoft.accessflow.security.api;

public class PasswordResetTokenNotFoundException extends RuntimeException {

    public PasswordResetTokenNotFoundException() {
        super("Password reset token not found");
    }
}
