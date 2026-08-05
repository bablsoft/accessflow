package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published after an approval-outcome prediction (issue AF-645) has been persisted for a query in
 * review — on the scored, skipped and failed paths alike ({@code probability} is {@code null} on
 * the sentinel rows). Published by the {@code ai} module's serving path; consumed by the realtime
 * module to push a WebSocket event so open review views refetch. Nothing publishes it until the
 * serving sub-issue lands.
 */
public record ApprovalPredictionCompletedEvent(UUID queryRequestId, UUID predictionId,
                                               Double probability) {
}
