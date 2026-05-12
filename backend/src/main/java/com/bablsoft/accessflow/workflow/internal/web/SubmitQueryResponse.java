package com.bablsoft.accessflow.workflow.internal.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.bablsoft.accessflow.core.api.QueryStatus;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SubmitQueryResponse(
        UUID id,
        QueryStatus status,
        Object aiAnalysis,
        Object reviewPlan,
        Instant estimatedReviewCompletion) {
}
