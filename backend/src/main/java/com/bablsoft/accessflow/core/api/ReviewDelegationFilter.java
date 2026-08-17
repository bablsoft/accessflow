package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Optional narrowing for the org-wide delegation listing (#622). Null fields mean "no filter";
 * {@code activeOnly} restricts to delegations currently conferring eligibility.
 */
public record ReviewDelegationFilter(UUID delegatorUserId,
                                     UUID delegateUserId,
                                     boolean activeOnly) {

    public static ReviewDelegationFilter none() {
        return new ReviewDelegationFilter(null, null, false);
    }
}
