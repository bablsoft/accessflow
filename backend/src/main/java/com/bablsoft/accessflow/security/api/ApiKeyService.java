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

    List<ApiKeyView> list(UUID userId);

    void revoke(UUID userId, UUID keyId);

    /**
     * Returns the owning user id when {@code rawKey} matches an active, unexpired, non-revoked
     * api_key row. Updates {@code last_used_at} on success.
     */
    Optional<UUID> resolveUserId(String rawKey);
}
