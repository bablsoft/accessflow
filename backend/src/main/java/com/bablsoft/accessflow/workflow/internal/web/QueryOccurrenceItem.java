package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryOccurrenceView;
import com.bablsoft.accessflow.core.api.QueryStatus;

import java.time.Instant;
import java.util.UUID;

/** Row in the {@code GET /queries/{id}/occurrences} response (#627). */
public record QueryOccurrenceItem(
        UUID id,
        QueryStatus status,
        Long rowsAffected,
        Integer executionDurationMs,
        Instant executedAt,
        String errorMessage,
        Instant createdAt) {

    public static QueryOccurrenceItem from(QueryOccurrenceView view) {
        return new QueryOccurrenceItem(
                view.id(),
                view.status(),
                view.rowsAffected(),
                view.executionDurationMs(),
                view.executionCompletedAt(),
                view.errorMessage(),
                view.createdAt());
    }
}
