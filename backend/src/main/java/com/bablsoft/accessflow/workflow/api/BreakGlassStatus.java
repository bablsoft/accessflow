package com.bablsoft.accessflow.workflow.api;

/**
 * Lifecycle of a break-glass retro-review (AF-385). A break-glass execution opens a
 * {@code PENDING_REVIEW} event; an admin reconciles it to {@code REVIEWED}. The executed query
 * itself is unaffected — it lands in its normal terminal {@code EXECUTED}/{@code FAILED} state.
 */
public enum BreakGlassStatus {
    PENDING_REVIEW,
    REVIEWED
}
