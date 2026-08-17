package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.ReviewDelegationStatus;
import com.bablsoft.accessflow.core.api.ReviewDelegationView;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shape of a review delegation (#622). */
public record ReviewDelegationResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("delegator") Party delegator,
        @JsonProperty("delegate") Party delegate,
        @JsonProperty("scope_kind") DelegationScopeKind scopeKind,
        @JsonProperty("scope_id") UUID scopeId,
        @JsonProperty("scope_name") String scopeName,
        @JsonProperty("reason") String reason,
        @JsonProperty("starts_at") Instant startsAt,
        @JsonProperty("ends_at") Instant endsAt,
        @JsonProperty("revoked_at") Instant revokedAt,
        @JsonProperty("status") ReviewDelegationStatus status,
        @JsonProperty("created_at") Instant createdAt) {

    public record Party(@JsonProperty("id") UUID id,
                        @JsonProperty("email") String email,
                        @JsonProperty("display_name") String displayName) {
    }

    public static ReviewDelegationResponse from(ReviewDelegationView view) {
        return new ReviewDelegationResponse(
                view.id(),
                new Party(view.delegatorUserId(), view.delegatorEmail(), view.delegatorDisplayName()),
                new Party(view.delegateUserId(), view.delegateEmail(), view.delegateDisplayName()),
                view.scopeKind(),
                view.scopeId(),
                view.scopeName(),
                view.reason(),
                view.startsAt(),
                view.endsAt(),
                view.revokedAt(),
                view.status(),
                view.createdAt());
    }

    /** Both directions in one payload: what the caller granted, and what was granted to them. */
    public record MyDelegations(@JsonProperty("granted") List<ReviewDelegationResponse> granted,
                                @JsonProperty("received") List<ReviewDelegationResponse> received) {
    }
}
