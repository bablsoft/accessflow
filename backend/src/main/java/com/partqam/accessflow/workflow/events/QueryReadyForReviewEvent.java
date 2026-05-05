package com.partqam.accessflow.workflow.events;

import java.util.UUID;

/**
 * Published when a query has transitioned from {@code PENDING_AI} to {@code PENDING_REVIEW}
 * (either the AI completed successfully and human review is required, or the AI failed). The
 * notifications module is the planned consumer.
 */
public record QueryReadyForReviewEvent(UUID queryRequestId) {
}
