package com.bablsoft.accessflow.core.api;

/**
 * A credential value references a secret-store provider that is not enabled in this deployment
 * ({@code accessflow.secrets.<provider>.enabled=false}). Thrown at datasource write time; maps
 * to HTTP 400. The user-facing detail is resolved by the exception handler
 * ({@code error.secret_provider_disabled}).
 */
public final class SecretProviderDisabledException extends SecretReferenceException {

    private final String provider;

    public SecretProviderDisabledException(String provider) {
        super("Secret provider not enabled: " + provider);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }
}
