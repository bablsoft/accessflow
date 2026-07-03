package com.bablsoft.accessflow.access.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only cross-module lookup of access grants with their scope and approval provenance.
 * Consumed by the workflow module's grant-covered auto-approval fast-path (#582) without reaching
 * into {@code access.internal}.
 */
public interface AccessGrantLookupService {

    /**
     * The user's {@code APPROVED}, unexpired grants on the datasource that opted into query
     * pre-approval ({@code pre_approve_queries = true}). Empty when none — including immediately
     * after expiry or revocation, which flip the grant status.
     */
    List<AccessGrantView> findActivePreApprovedGrants(UUID organizationId, UUID userId,
                                                      UUID datasourceId);

    /**
     * A grant by id regardless of status — the query-detail page must still render the grant
     * provenance after the grant expired or was revoked.
     */
    Optional<AccessGrantView> findGrant(UUID accessGrantId);
}
