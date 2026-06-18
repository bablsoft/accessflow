package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves organization-scoped facts for code paths that have no JWT in scope. {@link
 * #singleOrganization()} backs unauthenticated SSO/provider discovery, which assumes a single
 * deployment org (multi-org SSO login routing is future work); {@link #isDisabled(UUID)} is the
 * request-time tenant kill-switch used by the auth filters (AF-456).
 */
public interface OrganizationLookupService {

    UUID singleOrganization();

    Optional<String> findNameById(UUID organizationId);

    /**
     * Whether the organization is administratively disabled (AF-456). A disabled tenant's users are
     * blocked at login and at request time. Returns {@code false} for an unknown id.
     */
    boolean isDisabled(UUID organizationId);
}
