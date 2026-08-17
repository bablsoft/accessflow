package com.bablsoft.accessflow.core.internal.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for out-of-office reviewer delegation (#622).
 *
 * @param maxOpenPerDelegator how many delegations one user may have open at once. Bounds the
 *                            per-identity OR-tree the API-review queue builds, so a pathological
 *                            delegation set cannot produce an unbounded query.
 */
@ConfigurationProperties("accessflow.core.review-delegation")
public record ReviewDelegationProperties(@Min(1) int maxOpenPerDelegator) {

    public ReviewDelegationProperties {
        if (maxOpenPerDelegator <= 0) {
            maxOpenPerDelegator = 10;
        }
    }
}
