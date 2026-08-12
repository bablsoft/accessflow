package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module DTO for a row of {@code GET /queries/{id}/occurrences} (#627): one executed (or
 * failed) occurrence of a recurring query series. Each occurrence is a full {@code query_requests}
 * row — this view carries only the outcome summary the history table renders.
 */
public record QueryOccurrenceView(
        UUID id,
        QueryStatus status,
        Long rowsAffected,
        Integer executionDurationMs,
        Instant executionCompletedAt,
        String errorMessage,
        Instant createdAt) {
}
