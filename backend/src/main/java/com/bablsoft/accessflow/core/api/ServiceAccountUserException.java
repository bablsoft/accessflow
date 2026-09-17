package com.bablsoft.accessflow.core.api;

/**
 * Thrown by {@link UserProvisioningService#findOrProvision} when the email an external sign-in
 * (SAML / OAuth2) asserts belongs to a {@code SERVICE_ACCOUNT} (#869). Checked ahead of the
 * LOCAL-account conflict so the caller always learns the real reason — a service account
 * authenticates by API key only, and no IdP can mint it an interactive session.
 */
public class ServiceAccountUserException extends RuntimeException {

    private final String email;

    public ServiceAccountUserException(String email) {
        super("Account for " + email + " is a service account");
        this.email = email;
    }

    public String email() {
        return email;
    }
}
