package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.BreakGlassService.BreakGlassResult;

import java.util.UUID;

/**
 * Result of a synchronous break-glass execution (AF-385). {@code status} is the executed query's
 * terminal status (EXECUTED / FAILED); {@code eventId} is the opened retro-review.
 */
public record BreakGlassExecuteResponse(
        UUID id,
        UUID eventId,
        QueryStatus status,
        Long rowsAffected,
        Integer durationMs) {

    public static BreakGlassExecuteResponse from(BreakGlassResult result) {
        return new BreakGlassExecuteResponse(
                result.queryRequestId(),
                result.eventId(),
                result.status(),
                result.rowsAffected(),
                result.durationMs());
    }
}
