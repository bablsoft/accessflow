package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Input to {@code ReviewDelegationService.create} (#622).
 *
 * <p>{@code delegatorUserId} is the reviewer handing over duty — always the caller for the
 * self-service {@code /me} endpoints. {@code scopeKind} and {@code scopeId} are both null for an
 * unrestricted delegation and both set for a scoped one; the service rejects any other combination.
 */
public record CreateReviewDelegationCommand(UUID organizationId,
                                            UUID delegatorUserId,
                                            UUID delegateUserId,
                                            DelegationScopeKind scopeKind,
                                            UUID scopeId,
                                            String reason,
                                            Instant startsAt,
                                            Instant endsAt) {
}
