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
        String truncatedReason,
        String effectiveSql,
        long resultBytes) implements QueryExecutionResult {

    /** {@link #truncatedReason()} value when the configured row cap cut the result short. */
    public static final String TRUNCATED_ROW_LIMIT = "ROW_LIMIT";
    /** {@link #truncatedReason()} value when the configured byte cap cut the result short. */
    public static final String TRUNCATED_BYTE_LIMIT = "BYTE_LIMIT";
    /**
     * {@link #truncatedReason()} value when the reader's remaining data-budget allowance (#942)
     * cut the result short — set by the proxy's result-byte override trim and by the workflow when
     * the budget's row allowance was the binding row cap.
     */
    public static final String TRUNCATED_DATA_BUDGET = "DATA_BUDGET";

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
        this(columns, rows, rowCount, truncated, duration, Set.of(), Set.of(), null, null, 0L);
    }

    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration,
                                 Set<UUID> appliedMaskingPolicyIds) {
        this(columns, rows, rowCount, truncated, duration, appliedMaskingPolicyIds, Set.of(), null,
                null, 0L);
    }

    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration,
                                 Set<UUID> appliedMaskingPolicyIds,
                                 Set<UUID> appliedRowSecurityPolicyIds) {
        this(columns, rows, rowCount, truncated, duration, appliedMaskingPolicyIds,
                appliedRowSecurityPolicyIds, null, null, 0L);
    }

    /** Pre-#937 canonical shape — kept so published engine plugins stay binary-compatible. */
    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration,
                                 Set<UUID> appliedMaskingPolicyIds,
                                 Set<UUID> appliedRowSecurityPolicyIds, String truncatedReason) {
        this(columns, rows, rowCount, truncated, duration, appliedMaskingPolicyIds,
                appliedRowSecurityPolicyIds, truncatedReason, null, 0L);
    }

    /** Pre-#942 canonical shape — kept so published engine plugins stay binary-compatible. */
    public SelectExecutionResult(List<ResultColumn> columns, List<List<Object>> rows, long rowCount,
                                 boolean truncated, Duration duration,
                                 Set<UUID> appliedMaskingPolicyIds,
                                 Set<UUID> appliedRowSecurityPolicyIds, String truncatedReason,
                                 String effectiveSql) {
        this(columns, rows, rowCount, truncated, duration, appliedMaskingPolicyIds,
                appliedRowSecurityPolicyIds, truncatedReason, effectiveSql, 0L);
    }

    /** Returns a copy of this result with the given row-security policy ids attached. */
    public SelectExecutionResult withRowSecurityPolicyIds(Set<UUID> ids) {
        return new SelectExecutionResult(columns, rows, rowCount, truncated, duration,
                appliedMaskingPolicyIds, ids, truncatedReason, effectiveSql, resultBytes);
    }

    /**
     * Returns a copy carrying the statement as actually executed (#937) — the row-security /
     * soft-delete rewrite with bound values left as {@code ?}; {@code null} when nothing was
     * rewritten.
     */
    public SelectExecutionResult withEffectiveSql(String sql) {
        return new SelectExecutionResult(columns, rows, rowCount, truncated, duration,
                appliedMaskingPolicyIds, appliedRowSecurityPolicyIds, truncatedReason, sql,
                resultBytes);
    }

    /**
     * Returns a copy holding only the first {@code keptRows} rows, flagged truncated for
     * {@code reason}, with {@code bytes} as the delivered size — the proxy's byte trim (#942).
     */
    public SelectExecutionResult truncatedTo(int keptRows, String reason, long bytes) {
        return new SelectExecutionResult(columns, rows.subList(0, keptRows), keptRows, true, duration,
                appliedMaskingPolicyIds, appliedRowSecurityPolicyIds, reason, effectiveSql, bytes);
    }

    /** Returns a copy carrying the estimated delivered size in bytes (#942). */
    public SelectExecutionResult withResultBytes(long bytes) {
        return new SelectExecutionResult(columns, rows, rowCount, truncated, duration,
                appliedMaskingPolicyIds, appliedRowSecurityPolicyIds, truncatedReason, effectiveSql,
                bytes);
    }

    /** Returns a copy whose truncation is attributed to {@code reason}. */
    public SelectExecutionResult withTruncatedReason(String reason) {
        return new SelectExecutionResult(columns, rows, rowCount, truncated, duration,
                appliedMaskingPolicyIds, appliedRowSecurityPolicyIds, reason, effectiveSql,
                resultBytes);
    }
}
