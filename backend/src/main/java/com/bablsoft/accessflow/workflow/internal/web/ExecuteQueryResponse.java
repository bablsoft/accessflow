package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/** Response body for {@code POST /queries/{id}/execute}. */
public record ExecuteQueryResponse(
        UUID id,
        QueryStatus status,
        Long rowsAffected,
        Integer durationMs) {
}
