package com.bablsoft.accessflow.scim.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Long-lived SCIM bearer tokens, one or more named tokens per organization (#621). */
public interface ScimTokenService {

    List<ScimTokenView> list(UUID organizationId);

    /**
     * Create a named token; the raw value is available only on the returned object.
     *
     * @throws ScimTokenNameConflictException when the org already has a token with this name
     */
    IssuedScimToken create(UUID organizationId, String name, UUID createdBy);

    /**
     * Revoke the token (idempotent).
     *
     * @throws ScimTokenNotFoundException when the id is unknown in this org
     */
    void revoke(UUID organizationId, UUID tokenId);

    /**
     * Resolve a raw bearer token to its principal: empty when the token is unknown or revoked.
     * Bumps {@code last_used_at} best-effort.
     */
    Optional<ScimPrincipal> authenticate(String rawToken);
}
