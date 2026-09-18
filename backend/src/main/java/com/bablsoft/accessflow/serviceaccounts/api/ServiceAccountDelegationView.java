package com.bablsoft.accessflow.serviceaccounts.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A grant letting {@code serviceAccountUserId} act on behalf of {@code principalUserId} (#874).
 * Emails are resolved at read time and may be null when the user row is gone.
 */
public record ServiceAccountDelegationView(
        UUID id,
        UUID organizationId,
        UUID serviceAccountUserId,
        String serviceAccountEmail,
        UUID principalUserId,
        String principalEmail,
        UUID grantedBy,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        ServiceAccountDelegationStatus status) {

    public static ServiceAccountDelegationStatus statusAt(Instant revokedAt, Instant expiresAt, Instant now) {
        if (revokedAt != null) {
            return ServiceAccountDelegationStatus.REVOKED;
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return ServiceAccountDelegationStatus.EXPIRED;
        }
        return ServiceAccountDelegationStatus.ACTIVE;
    }
}
