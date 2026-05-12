package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query request has been persisted in {@code PENDING_AI} and is ready for
 * asynchronous AI analysis. Consumed by the {@code ai} module's analysis listener.
 */
public record QuerySubmittedEvent(UUID queryRequestId) {
}
