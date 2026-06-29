package com.bablsoft.accessflow.lifecycle.api;

/**
 * Lifecycle of a right-to-erasure (deletion) request. Mirrors the PostgreSQL {@code erasure_status}
 * enum and the query-review state machine.
 *
 * <pre>
 * PENDING_SCOPE_AI ─► PENDING_REVIEW ─┬─► APPROVED ─┬─► EXECUTED
 *                                     │             └─► FAILED      (execution error)
 *                                     └─► REJECTED                 (reviewer rejection)
 * PENDING_SCOPE_AI / PENDING_REVIEW ─► CANCELLED                   (requester cancel)
 * </pre>
 */
public enum ErasureStatus {
    PENDING_SCOPE_AI,
    PENDING_REVIEW,
    APPROVED,
    EXECUTED,
    REJECTED,
    FAILED,
    CANCELLED
}
