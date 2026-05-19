package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when an admin or reviewer triggers a re-run of AI analysis on a query whose
 * previous analysis failed. The previously failed {@code ai_analyses} row has already been
 * removed by the publisher. Consumed by the {@code ai} module's analysis listener, which
 * invokes the normal {@code analyzeSubmittedQuery} pipeline.
 */
public record AiReanalysisRequestedEvent(UUID queryRequestId, UUID requestedByUserId) {
}
