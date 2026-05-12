package com.bablsoft.accessflow.workflow.internal.web;

import com.fasterxml.jackson.annotation.JsonRawValue;

/** Response body for {@code GET /queries/{id}/results}. */
public record QueryResultsPageResponse(
        @JsonRawValue String columns,
        @JsonRawValue String rows,
        long rowCount,
        boolean truncated,
        int page,
        int size) {
}
