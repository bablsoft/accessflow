package com.bablsoft.accessflow.security.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of per-user API keys used to authenticate MCP and REST calls without a
 * browser session. Keys are issued once (the plaintext is returned by {@link #issue}), stored as
 * a SHA-256 hash, and revoked individually. Lookups for the auth filter go through
 * {@link #resolve(String)}, which honours expiry and revocation.
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
     * never authenticate via {@link #resolve(String)}). The plaintext is never persisted. The row
     * is marked {@code bootstrapDeclared} and, in the same transaction, the flag is cleared on the
     * user's other keys (#871) — a renamed declared key demotes the previous row to an ordinary,
     * revocable key.
     */
    ApiKeyView importOrUpdate(UUID userId, UUID organizationId, String name, String rawKey, Instant expiresAt);

    List<ApiKeyView> list(UUID userId);

    /**
     * Every key of every user in {@code userIds}, newest first per user, keyed by owner (#871).
     * Users without keys are absent from the map. One query, for the admin listing.
     */
    Map<UUID, List<ApiKeyView>> listByUserIds(Collection<UUID> userIds);

    /**
     * Revokes the key. Idempotent. Throws {@link ApiKeyNotFoundException} when the key does not
     * exist or belongs to another user, and {@link ApiKeyBootstrapDeclaredException} when it is the
     * key the bootstrap reconciler declared (#871) — a changed reconcile would reactivate it.
     */
    void revoke(UUID userId, UUID keyId);

    /**
     * Sets the key's expiry — the primitive behind grace-window rotation (#871): the replacement is
     * issued and the superseded key keeps authenticating until {@code expiresAt}. Owner-scoped like
     * {@link #revoke}: throws {@link ApiKeyNotFoundException} for an unknown or foreign key, and
     * {@link ApiKeyBootstrapDeclaredException} for the declared key (a changed reconcile re-asserts
     * its expiry from the spec). Does not touch {@code revoked_at}.
     */
    void expireAt(UUID userId, UUID keyId, Instant expiresAt);

    /**
     * Returns the matched key's id and owning user id when {@code rawKey} matches an active,
     * unexpired, non-revoked api_key row. Updates {@code last_used_at} on success (#869).
     */
    Optional<ResolvedApiKey> resolve(String rawKey);

    /**
     * Returns only the owning user id — see {@link #resolve(String)}. Kept for callers that
     * predate the key id being carried downstream.
     */
    default Optional<UUID> resolveUserId(String rawKey) {
        return resolve(rawKey).map(ResolvedApiKey::userId);
    }
}
