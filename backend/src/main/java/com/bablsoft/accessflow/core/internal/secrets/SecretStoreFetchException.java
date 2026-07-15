package com.bablsoft.accessflow.core.internal.secrets;

/**
 * Internal store-level fetch failure. Carries a developer-facing summary (store unreachable,
 * secret missing, field missing) — never the secret value. {@link DefaultSecretResolutionService}
 * wraps it into the user-facing, i18n-resolved
 * {@link com.bablsoft.accessflow.core.api.SecretResolutionException}.
 */
class SecretStoreFetchException extends RuntimeException {

    SecretStoreFetchException(String message) {
        super(message);
    }

    SecretStoreFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
