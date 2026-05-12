package com.bablsoft.accessflow.workflow.events;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Published when query execution finishes — successfully ({@code finalStatus = EXECUTED}) or
 * with a runtime failure ({@code finalStatus = FAILED}). Drives the realtime
 * {@code query.executed} push to the submitter.
 */
public record QueryExecutedEvent(
        UUID queryRequestId,
        Long rowsAffected,
        long durationMs,
        QueryStatus finalStatus) {
}
