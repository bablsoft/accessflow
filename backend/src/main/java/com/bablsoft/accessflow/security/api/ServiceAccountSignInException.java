package com.bablsoft.accessflow.security.api;

/**
 * Thrown when a {@code SERVICE_ACCOUNT} principal attempts to obtain an interactive session —
 * password login, refresh, or the SAML / OAuth2 exchange (#869). Service accounts authenticate by
 * API key only; the rule is enforced here rather than left to the unusable password hash
 * bootstrap happens to seed. Maps to HTTP 401 {@code SERVICE_ACCOUNT_SIGN_IN_BLOCKED}.
 */
public class ServiceAccountSignInException extends RuntimeException {

    public ServiceAccountSignInException() {
        super("Service accounts cannot sign in interactively");
    }
}
