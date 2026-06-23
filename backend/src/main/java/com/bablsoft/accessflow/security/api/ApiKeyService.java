package com.bablsoft.accessflow.security.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of per-user API keys used to authenticate MCP and REST calls without a
 * browser session. Keys are issued once (the plaintext is returned by {@link #issue}), stored as
 * a SHA-256 hash, and revoked individually. Lookups for the auth filter go through
 * {@link #resolveUserId(String)}, which honours expiry and revocation.
 */
public interface ApiKeyService {

    IssuedApiKey issue(UUID userId, UUID organizationId, String name, Instant expiresAt);

    /**
     * Stores a caller-supplied raw key (rather than generating one) for declarative provisioning —
     * e.g. the {@code bootstrap} module seeding a service-account key whose plaintext the operator
     * already holds in a Secret. Authoritative-upsert: when a key named {@code name} already exists
     * for {@code userId} its hash / prefix / expiry are overwritten in place (and any prior
     * revocation cleared); otherwise a new row is created. The raw key must carry the standard
     * {@code af_} shape or an {@link IllegalArgumentException} is thrown (a key without it could
     * never authenticate via {@link #resolveUserId(String)}). The plaintext is never persisted.
     */
    ApiKeyView importOrUpdate(UUID userId, UUID organizationId, String name, String rawKey, Instant expiresAt);

    List<ApiKeyView> list(UUID userId);

    void revoke(UUID userId, UUID keyId);

    /**
     * Returns the owning user id when {@code rawKey} matches an active, unexpired, non-revoked
     * api_key row. Updates {@code last_used_at} on success.
     */
    Optional<UUID> resolveUserId(String rawKey);
}
