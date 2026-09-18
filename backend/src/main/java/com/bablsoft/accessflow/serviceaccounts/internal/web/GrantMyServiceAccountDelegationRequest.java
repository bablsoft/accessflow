package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.GrantServiceAccountDelegationCommand;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** Self-service grant: the caller lets the named service account act for them (#874). */
public record GrantMyServiceAccountDelegationRequest(
        @NotNull(message = "{validation.service_account_delegation_service_account.required}")
        UUID serviceAccountUserId,

        Instant expiresAt
) {
    public GrantServiceAccountDelegationCommand toCommand(UUID principalUserId) {
        return new GrantServiceAccountDelegationCommand(serviceAccountUserId, principalUserId, expiresAt);
    }
}
