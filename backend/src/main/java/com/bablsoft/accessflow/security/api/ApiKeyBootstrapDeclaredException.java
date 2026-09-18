package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * Thrown when a caller tries to revoke or expire the API key the bootstrap reconciler declared for
 * a service account (#871). {@code ApiKeyService.importOrUpdate} clears {@code revoked_at} and
 * re-asserts {@code expires_at} on every changed reconcile, so either write would only appear to
 * succeed until the next restart — the guard sits at the chokepoint so the account's own
 * {@code /me/api-keys} path is covered too. The remediation is to rotate the secret in the
 * bootstrap source and restart.
 */
public class ApiKeyBootstrapDeclaredException extends RuntimeException {

    private final UUID apiKeyId;

    public ApiKeyBootstrapDeclaredException(UUID apiKeyId) {
        super("API key is bootstrap-declared and cannot be revoked or expired: " + apiKeyId);
        this.apiKeyId = apiKeyId;
    }

    public UUID apiKeyId() {
        return apiKeyId;
    }
}
