package com.bablsoft.accessflow.security.api;

import java.util.UUID;

public interface AuthenticationService {
    AuthResult login(LoginCommand command);

    AuthResult refresh(String refreshToken);

    void logout(String refreshToken);

    /**
     * Issue a fresh JWT pair for a user whose identity has already been verified out-of-band
     * (currently: after an OAuth2 redirect dance). The caller is responsible for proving the
     * user's identity before invoking this method.
     */
    AuthResult issueForUser(UUID userId);
}
