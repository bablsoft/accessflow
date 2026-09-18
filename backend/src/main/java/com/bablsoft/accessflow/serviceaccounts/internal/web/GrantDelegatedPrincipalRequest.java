package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.GrantServiceAccountDelegationCommand;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** Admin grant: which human the service account in the path may act for (#874). */
public record GrantDelegatedPrincipalRequest(
        @NotNull(message = "{validation.service_account_delegation_principal.required}")
        UUID principalUserId,

        Instant expiresAt
) {
    public GrantServiceAccountDelegationCommand toCommand(UUID serviceAccountUserId) {
        return new GrantServiceAccountDelegationCommand(serviceAccountUserId, principalUserId, expiresAt);
    }
}
