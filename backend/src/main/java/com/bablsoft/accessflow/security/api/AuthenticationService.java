package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * Interactive session issuance. Every method that mints a JWT pair — {@link #login},
 * {@link #refresh} and {@link #issueForUser} — throws {@link ServiceAccountSignInException} for
 * a {@code SERVICE_ACCOUNT} principal: service accounts authenticate by API key only (#869).
 */
public interface AuthenticationService {
    AuthResult login(LoginCommand command);

    AuthResult refresh(String refreshToken);

    void logout(String refreshToken);

    /**
     * Issue a fresh JWT pair for a user whose identity has already been verified out-of-band
     * (currently: after a SAML or OAuth2 redirect dance). The caller is responsible for proving
     * the user's identity before invoking this method.
     */
    AuthResult issueForUser(UUID userId);
}
