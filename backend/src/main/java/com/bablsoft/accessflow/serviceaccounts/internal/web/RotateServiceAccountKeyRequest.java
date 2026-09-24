package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.RotateServiceAccountKeyCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code gracePeriod} is how long the superseded key keeps authenticating; absent means the
 * configured default ({@code accessflow.serviceaccounts.rotation-grace}), present must be positive.
 * {@code applicationName} absent means the replacement inherits the superseded key's (#938).
 */
public record RotateServiceAccountKeyRequest(
        @NotBlank(message = "{validation.service_account_key_name.required}")
        @Size(max = 100, message = "{validation.service_account_key_name.size}")
        String name,

        Instant expiresAt,

        Duration gracePeriod,

        @Size(max = 100, message = "{validation.api_key.application_name.size}")
        @Pattern(regexp = "[^\\p{Cntrl}]*", message = "{validation.api_key.application_name.pattern}")
        String applicationName
) {
    @AssertTrue(message = "{validation.service_account_key_grace.positive}")
    public boolean isGracePeriodPositive() {
        return gracePeriod == null || (!gracePeriod.isNegative() && !gracePeriod.isZero());
    }

    public RotateServiceAccountKeyCommand toCommand() {
        return new RotateServiceAccountKeyCommand(name, expiresAt, gracePeriod, applicationName);
    }
}
