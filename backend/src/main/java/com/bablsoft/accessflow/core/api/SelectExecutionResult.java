package com.bablsoft.accessflow.core.api;

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
        Set<UUID> appliedRowSecurityPolicyIds,
        String truncatedReason) implements QueryExecutionResult {

    /** {@link #truncatedReason()} value when the configured row cap cut the result short. */
    public static final String TRUNCATED_ROW_LIMIT = "ROW_LIMIT";
    /** {@link #truncatedReason()} value when the configured byte cap cut the result short. */
    public static final String TRUNCATED_BYTE_LIMIT = "BYTE_LIMIT";

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
        this(columns, rows, rowCount, truncated, duration, Set.of(), Set.of(), null);
    }

    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration,
                                 Set<UUID> appliedMaskingPolicyIds) {
        this(columns, rows, rowCount, truncated, duration, appliedMaskingPolicyIds, Set.of(), null);
    }

    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration,
                                 Set<UUID> appliedMaskingPolicyIds,
                                 Set<UUID> appliedRowSecurityPolicyIds) {
        this(columns, rows, rowCount, truncated, duration, appliedMaskingPolicyIds,
                appliedRowSecurityPolicyIds, null);
    }

    /** Returns a copy of this result with the given row-security policy ids attached. */
    public SelectExecutionResult withRowSecurityPolicyIds(Set<UUID> ids) {
        return new SelectExecutionResult(columns, rows, rowCount, truncated, duration,
                appliedMaskingPolicyIds, ids, truncatedReason);
    }
}
