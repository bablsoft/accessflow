package com.bablsoft.accessflow.requestgroups.api;

/**
 * Lifecycle of a grouped request. Mirrors the per-query {@code QueryStatus} so reviewers, audit, and
 * notifications reuse familiar states, with two group-specific terminal states:
 * {@code PARTIALLY_EXECUTED} (the ordered run stopped after a member failed) and {@code EXECUTING}
 * (the run is in progress).
 *
 * <pre>
 * DRAFT → PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTING → EXECUTED
 *                                    ↘ REJECTED
 *                                    ↘ TIMED_OUT
 *       (PENDING_AI/PENDING_REVIEW/scheduled APPROVED) ↘ CANCELLED   (submitter)
 * EXECUTING → PARTIALLY_EXECUTED   (stopped mid-sequence, continue_on_error=false)
 * EXECUTING → FAILED               (first member failed)
 * </pre>
 */
public enum RequestGroupStatus {
    DRAFT,
    PENDING_AI,
    PENDING_REVIEW,
    APPROVED,
    EXECUTING,
    EXECUTED,
    REJECTED,
    TIMED_OUT,
    PARTIALLY_EXECUTED,
    FAILED,
    CANCELLED
}
