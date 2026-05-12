package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when AI analysis could not produce a valid result (provider error, malformed JSON,
 * etc.). A sentinel {@code CRITICAL} analysis row is still persisted so the query workflow can
 * surface the failure to a human reviewer.
 */
public record AiAnalysisFailedEvent(UUID queryRequestId, String reason) {
}
