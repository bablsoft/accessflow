package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when the AI analyzer intentionally bypasses analysis (e.g. the datasource has
 * {@code ai_analysis_enabled = false}). Distinct from {@link AiAnalysisFailedEvent}: no sentinel
 * {@code ai_analyses} row is persisted, and the frontend must not render a "failed" affordance.
 * The workflow state machine consumes this to advance the query out of {@code PENDING_AI}.
 */
public record AiAnalysisSkippedEvent(UUID queryRequestId, String reason) {
}
