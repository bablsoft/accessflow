package com.bablsoft.accessflow.security.api;

public record LoginCommand(String email, String password, String totpCode) {

    public LoginCommand(String email, String password) {
        this(email, password, null);
    }
}
