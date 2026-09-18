package com.bablsoft.accessflow.serviceaccounts.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the service-accounts module (#871).
 *
 * <ul>
 *   <li>{@code rotationGrace} — how long a superseded API key keeps authenticating after an admin
 *       rotates it, when the request names no {@code gracePeriod}. Rotation issues the replacement
 *       and <em>expires</em> the old key at {@code now + rotationGrace} rather than revoking it,
 *       so a running agent or CI job is never cut off mid-deploy. Non-positive values fall back to
 *       the default.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.serviceaccounts")
public record ServiceAccountsProperties(Duration rotationGrace) {

    public static final Duration DEFAULT_ROTATION_GRACE = Duration.ofHours(24);

    public ServiceAccountsProperties {
        if (rotationGrace == null || rotationGrace.isNegative() || rotationGrace.isZero()) {
            rotationGrace = DEFAULT_ROTATION_GRACE;
        }
    }
}
