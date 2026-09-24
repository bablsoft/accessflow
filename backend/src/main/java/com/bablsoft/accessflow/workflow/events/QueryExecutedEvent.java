package com.bablsoft.accessflow.workflow.events;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Published when query execution finishes — successfully ({@code finalStatus = EXECUTED}) or
 * with a runtime failure ({@code finalStatus = FAILED}). Drives the realtime
 * {@code query.executed} push to the submitter. {@code recurringParentId} is non-null only for
 * occurrence rows of a recurring series (#627) — it gates the result-delivery notification.
 * {@code effectiveSql} is the statement as actually executed — row-security / soft-delete
 * rewrite, bound values redacted as {@code ?} — or {@code null} when nothing was rewritten (#937).
 *
 * <p>Published outside any transaction, so consumers must be plain {@code @EventListener}s —
 * an {@code @ApplicationModuleListener} (AFTER_COMMIT) would silently never fire.
 */
public record QueryExecutedEvent(
        UUID queryRequestId,
        Long rowsAffected,
        long durationMs,
        QueryStatus finalStatus,
        UUID recurringParentId,
        String effectiveSql) {

    /** Backward-compatible constructor without the #627 series back-pointer. */
    public QueryExecutedEvent(UUID queryRequestId, Long rowsAffected, long durationMs,
                              QueryStatus finalStatus) {
        this(queryRequestId, rowsAffected, durationMs, finalStatus, null, null);
    }

    /** Backward-compatible constructor without the #937 effective statement. */
    public QueryExecutedEvent(UUID queryRequestId, Long rowsAffected, long durationMs,
                              QueryStatus finalStatus, UUID recurringParentId) {
        this(queryRequestId, rowsAffected, durationMs, finalStatus, recurringParentId, null);
    }
}
