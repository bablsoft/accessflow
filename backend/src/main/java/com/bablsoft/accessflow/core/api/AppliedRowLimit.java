package com.bablsoft.accessflow.core.api;

import java.util.Set;
import java.util.UUID;

/**
 * The row cap the matching row-limit policies impose on one query (#934): the lowest
 * {@code max_rows} among them, and the ids of every matching policy that carries that value.
 */
public record AppliedRowLimit(int maxRows, Set<UUID> policyIds) {

    public AppliedRowLimit {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        policyIds = policyIds == null ? Set.of() : Set.copyOf(policyIds);
    }

    /** The smaller of a nullable cap and this limit — policies only ever lower a cap. */
    public int tighten(Integer cap) {
        return cap == null ? maxRows : Math.min(cap, maxRows);
    }
}
