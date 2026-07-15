package com.bablsoft.accessflow.core.internal.secrets;

/**
 * Internal SPI over one external secret-store provider (AF-448). Implementations are created by
 * {@link SecretsConfiguration} only when their provider is enabled, so
 * {@link DefaultSecretResolutionService}'s injected list contains exactly the enabled providers.
 * Implementations fetch on every call — resolved plaintext is never cached — and must not log
 * or retain secret values.
 */
interface SecretStore {

    /** Stable provider id: {@code vault}, {@code aws}, or {@code azure}. */
    String providerId();

    /**
     * Fetch the secret value for a parsed reference of this provider.
     *
     * @throws SecretStoreFetchException when the store call fails or the secret/field is missing
     */
    String fetch(SecretReference reference);
}
