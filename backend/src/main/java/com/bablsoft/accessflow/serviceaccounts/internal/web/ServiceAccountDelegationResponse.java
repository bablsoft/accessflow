package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationStatus;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationView;

import java.time.Instant;
import java.util.UUID;

public record ServiceAccountDelegationResponse(
        UUID id,
        UUID serviceAccountUserId,
        String serviceAccountEmail,
        UUID principalUserId,
        String principalEmail,
        UUID grantedBy,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        ServiceAccountDelegationStatus status
) {
    public static ServiceAccountDelegationResponse from(ServiceAccountDelegationView view) {
        return new ServiceAccountDelegationResponse(
                view.id(),
                view.serviceAccountUserId(),
                view.serviceAccountEmail(),
                view.principalUserId(),
                view.principalEmail(),
                view.grantedBy(),
                view.createdAt(),
                view.expiresAt(),
                view.revokedAt(),
                view.status());
    }
}
