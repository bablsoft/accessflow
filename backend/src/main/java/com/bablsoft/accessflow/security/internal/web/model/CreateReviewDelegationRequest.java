package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/me/review-delegations} (#622).
 *
 * <p>{@code scopeKind} and {@code scopeId} are either both absent (an unrestricted delegation) or
 * both present. That pairing, the window ordering, and the delegate's membership are checked in the
 * service, which owns the invariant — Bean Validation covers only the per-field shape.
 */
public record CreateReviewDelegationRequest(
        @JsonProperty("delegate_user_id")
        @NotNull(message = "{validation.review_delegation.delegate_required}")
        UUID delegateUserId,

        @JsonProperty("scope_kind")
        DelegationScopeKind scopeKind,

        @JsonProperty("scope_id")
        UUID scopeId,

        @JsonProperty("reason")
        @Size(max = 500, message = "{validation.review_delegation.reason_too_long}")
        String reason,

        @JsonProperty("starts_at")
        @NotNull(message = "{validation.review_delegation.starts_at_required}")
        Instant startsAt,

        @JsonProperty("ends_at")
        @NotNull(message = "{validation.review_delegation.ends_at_required}")
        Instant endsAt) {
}
