package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/**
 * The one chokepoint that turns a {@code users} row into a service account (#868). It flips
 * {@code users.principal_type} to {@code SERVICE_ACCOUNT} and creates the {@code service_accounts}
 * detail row in the same transaction, so the discriminator and the detail row can never drift.
 * The bootstrap reconciler calls it on create and update; the admin CRUD (#871) will reuse it.
 */
public interface ServiceAccountProvisioningService {

    /**
     * Registers {@code userId} as a service account of {@code organizationId} owned by
     * {@code managedBy}. Idempotent: an existing detail row only has {@code managed_by} re-asserted
     * — description, owner, tool allow-list and rate limits are never touched, which is what lets
     * an admin's UI edit survive a bootstrap re-run. Throws
     * {@link com.bablsoft.accessflow.core.api.UserNotFoundException} when the user is not in the
     * organization.
     */
    ServiceAccountView ensureRegistered(UUID organizationId, UUID userId, ServiceAccountSource managedBy);
}
