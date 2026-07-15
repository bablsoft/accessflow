package com.bablsoft.accessflow.core.api;

/**
 * A credential value looked like a secret reference ({@code vault:…} / {@code aws:…} /
 * {@code azure:…}) but does not match the provider's reference syntax. Thrown at datasource
 * write time; maps to HTTP 400. The user-facing detail is resolved by the exception handler
 * ({@code error.invalid_secret_reference}) — the reference itself is not a secret and is safe
 * to echo.
 */
public final class InvalidSecretReferenceException extends SecretReferenceException {

    private final String reference;

    public InvalidSecretReferenceException(String reference) {
        super("Invalid secret reference: " + reference);
        this.reference = reference;
    }

    public String reference() {
        return reference;
    }
}
