package com.bablsoft.accessflow.core.api;

/**
 * Base type for external secret-reference failures (AF-448). A datasource credential column may
 * hold a secret reference ({@code vault:…} / {@code aws:…} / {@code azure:…}) instead of local
 * AES-256-GCM ciphertext; these exceptions cover the reference lifecycle: malformed shape at
 * write time, provider not enabled in this deployment, and store failures at resolve time.
 */
public abstract sealed class SecretReferenceException extends RuntimeException
        permits InvalidSecretReferenceException, SecretProviderDisabledException,
        SecretResolutionException {

    protected SecretReferenceException(String message) {
        super(message);
    }

    protected SecretReferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
