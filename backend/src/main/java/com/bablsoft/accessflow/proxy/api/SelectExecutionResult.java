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
        Set<UUID> appliedMaskingPolicyIds) implements QueryExecutionResult {

    public SelectExecutionResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        appliedMaskingPolicyIds = appliedMaskingPolicyIds == null
                ? Set.of() : Set.copyOf(appliedMaskingPolicyIds);
    }

    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration) {
        this(columns, rows, rowCount, truncated, duration, Set.of());
    }
}
