package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.RotateServiceAccountKeyCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code gracePeriod} is how long the superseded key keeps authenticating; absent means the
 * configured default ({@code accessflow.serviceaccounts.rotation-grace}), present must be positive.
 */
public record RotateServiceAccountKeyRequest(
        @NotBlank(message = "{validation.service_account_key_name.required}")
        @Size(max = 100, message = "{validation.service_account_key_name.size}")
        String name,

        Instant expiresAt,

        Duration gracePeriod
) {
    @AssertTrue(message = "{validation.service_account_key_grace.positive}")
    public boolean isGracePeriodPositive() {
        return gracePeriod == null || (!gracePeriod.isNegative() && !gracePeriod.isZero());
    }

    public RotateServiceAccountKeyCommand toCommand() {
        return new RotateServiceAccountKeyCommand(name, expiresAt, gracePeriod);
    }
}
