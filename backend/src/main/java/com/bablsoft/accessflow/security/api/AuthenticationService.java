package com.bablsoft.accessflow.security.api;

public interface AuthenticationService {
    AuthResult login(LoginCommand command);
    AuthResult refresh(String refreshToken);
    void logout(String refreshToken);
}
