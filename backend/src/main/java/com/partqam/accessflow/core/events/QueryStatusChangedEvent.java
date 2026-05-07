package com.partqam.accessflow.core.events;

import com.partqam.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Published from the single chokepoint that mutates {@code query_requests.status} every time
 * the value changes — review-driven, AI-driven, execution-driven, or cancellation. Carries
 * just enough context for the realtime module to fan the change out to the submitter.
 */
public record QueryStatusChangedEvent(
        UUID queryRequestId,
        UUID submitterId,
        QueryStatus oldStatus,
        QueryStatus newStatus) {
}
