package com.bablsoft.accessflow.core.api;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

public record UpdateExecutionResult(long rowsAffected, Duration duration,
                                    Set<UUID> appliedRowSecurityPolicyIds,
                                    String effectiveSql)
        implements QueryExecutionResult {

    public UpdateExecutionResult {
        appliedRowSecurityPolicyIds = appliedRowSecurityPolicyIds == null
                ? Set.of() : Set.copyOf(appliedRowSecurityPolicyIds);
    }

    public UpdateExecutionResult(long rowsAffected, Duration duration) {
        this(rowsAffected, duration, Set.of(), null);
    }

    /** Pre-#937 canonical shape — kept so published engine plugins stay binary-compatible. */
    public UpdateExecutionResult(long rowsAffected, Duration duration,
                                 Set<UUID> appliedRowSecurityPolicyIds) {
        this(rowsAffected, duration, appliedRowSecurityPolicyIds, null);
    }
}
