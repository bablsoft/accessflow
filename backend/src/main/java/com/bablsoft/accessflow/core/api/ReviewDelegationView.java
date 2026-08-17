package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A review delegation as read back (#622). {@code status} and {@code scopeName} are derived at read
 * time, not stored.
 */
public record ReviewDelegationView(UUID id,
                                   UUID organizationId,
                                   UUID delegatorUserId,
                                   String delegatorDisplayName,
                                   String delegatorEmail,
                                   UUID delegateUserId,
                                   String delegateDisplayName,
                                   String delegateEmail,
                                   DelegationScopeKind scopeKind,
                                   UUID scopeId,
                                   String scopeName,
                                   String reason,
                                   Instant startsAt,
                                   Instant endsAt,
                                   Instant revokedAt,
                                   ReviewDelegationStatus status,
                                   Instant createdAt) {
}
