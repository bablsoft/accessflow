package com.bablsoft.accessflow.core.api;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

public record UpdateExecutionResult(long rowsAffected, Duration duration,
                                    Set<UUID> appliedRowSecurityPolicyIds)
        implements QueryExecutionResult {

    public UpdateExecutionResult {
        appliedRowSecurityPolicyIds = appliedRowSecurityPolicyIds == null
                ? Set.of() : Set.copyOf(appliedRowSecurityPolicyIds);
    }

    public UpdateExecutionResult(long rowsAffected, Duration duration) {
        this(rowsAffected, duration, Set.of());
    }
}
