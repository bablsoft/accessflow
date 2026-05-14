package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent organization creation used by the startup bootstrap layer. Distinct from
 * {@link BootstrapService}: the setup wizard creates the first org-and-admin pair atomically and
 * rejects subsequent calls; this contract is the lower-level seam that the env-var bootstrap
 * reconciler uses to upsert the single deployment organization on every restart.
 */
public interface OrganizationProvisioningService {

    Optional<UUID> findBySlug(String slug);

    /**
     * Create a new organization. {@code requestedSlug} is used verbatim when non-blank, otherwise
     * the slug is derived from {@code name}. The implementation guarantees slug uniqueness by
     * appending a random suffix on collision.
     */
    UUID create(String name, String requestedSlug);
}
