package com.bablsoft.accessflow.core.api;

/**
 * Resolving a secret reference through its external store failed at credential-use time
 * (store unreachable, secret or field missing, provider disabled after the reference was
 * stored). Maps to HTTP 502 — the failure is an external dependency, not user input. The
 * message varies per failure and is resolved at the throw site via {@code MessageSource}
 * (same convention as {@link DriverResolutionException}); it never contains the secret value.
 */
public final class SecretResolutionException extends SecretReferenceException {

    private final String provider;
    private final String reference;

    public SecretResolutionException(String provider, String reference, String message) {
        super(message);
        this.provider = provider;
        this.reference = reference;
    }

    public SecretResolutionException(String provider, String reference, String message,
                                     Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.reference = reference;
    }

    public String provider() {
        return provider;
    }

    public String reference() {
        return reference;
    }
}
