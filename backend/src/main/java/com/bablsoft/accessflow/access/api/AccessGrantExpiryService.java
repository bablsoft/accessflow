package com.bablsoft.accessflow.access.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Drives the {@code AccessGrantExpiryJob}: finds APPROVED grants past their {@code expires_at} and
 * revokes them. Split from the job so the scheduling concern stays thin and the logic is unit-testable.
 */
public interface AccessGrantExpiryService {

    List<UUID> findExpiredGrantedIds(Instant now);

    /**
     * Revokes the materialised permission and transitions the request {@code APPROVED → EXPIRED}.
     * Idempotent: returns {@code false} (without throwing) when the row is no longer APPROVED — an
     * admin revoke may have raced the job.
     */
    boolean expireAndRevoke(UUID accessRequestId);
}
