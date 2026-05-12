package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query has transitioned from {@code PENDING_AI} to {@code PENDING_REVIEW}
 * (either the AI completed successfully and human review is required, or the AI failed). The
 * audit and notifications modules are the consumers.
 */
public record QueryReadyForReviewEvent(UUID queryRequestId) {
}
