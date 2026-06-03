package com.bablsoft.accessflow.proxy.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record SelectExecutionResult(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        long rowCount,
        boolean truncated,
        Duration duration,
        Set<UUID> appliedMaskingPolicyIds,
        Set<UUID> appliedRowSecurityPolicyIds) implements QueryExecutionResult {

    public SelectExecutionResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        appliedMaskingPolicyIds = appliedMaskingPolicyIds == null
                ? Set.of() : Set.copyOf(appliedMaskingPolicyIds);
        appliedRowSecurityPolicyIds = appliedRowSecurityPolicyIds == null
                ? Set.of() : Set.copyOf(appliedRowSecurityPolicyIds);
    }

    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration) {
        this(columns, rows, rowCount, truncated, duration, Set.of(), Set.of());
    }

    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration,
                                 Set<UUID> appliedMaskingPolicyIds) {
        this(columns, rows, rowCount, truncated, duration, appliedMaskingPolicyIds, Set.of());
    }

    /** Returns a copy of this result with the given row-security policy ids attached. */
    public SelectExecutionResult withRowSecurityPolicyIds(Set<UUID> ids) {
        return new SelectExecutionResult(columns, rows, rowCount, truncated, duration,
                appliedMaskingPolicyIds, ids);
    }
}
