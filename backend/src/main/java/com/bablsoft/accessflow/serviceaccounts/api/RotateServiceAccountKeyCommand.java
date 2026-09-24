package com.bablsoft.accessflow.serviceaccounts.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Input to {@link ServiceAccountAdminService#rotateKey}: the replacement's {@code name} and optional
 * {@code expiresAt}, and the {@code gracePeriod} the superseded key keeps authenticating for — null
 * falls back to {@code accessflow.serviceaccounts.rotation-grace}. Must be positive when set.
 * {@code applicationName} (#938) null = the replacement inherits the superseded key's name.
 */
public record RotateServiceAccountKeyCommand(String name, Instant expiresAt, Duration gracePeriod,
                                             String applicationName) {

    public RotateServiceAccountKeyCommand(String name, Instant expiresAt, Duration gracePeriod) {
        this(name, expiresAt, gracePeriod, null);
    }
}
