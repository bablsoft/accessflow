package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published after an approval-outcome prediction (issue AF-645) has been persisted for a query in
 * review — on the scored, skipped and failed paths alike ({@code probability} is {@code null} on
 * the sentinel rows). Published by the {@code ai} module's serving path; consumed by the realtime
 * module, which pushes {@code query.prediction_complete} to the query's eligible reviewers and its
 * submitter so open review views refetch.
 */
public record ApprovalPredictionCompletedEvent(UUID queryRequestId, UUID predictionId,
                                               Double probability) {
}
