package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Everything needed to persist one {@code approval_predictions} row (issue AF-645). Mirrors
 * {@link PersistQueryEstimateCommand}: the {@code ai} module scores the query and hands the values
 * to {@link ApprovalPredictionPersistenceService}. {@code featuresJson} is the serving-time
 * feature snapshot and is expected to carry a boolean {@code estimate_missing} key written by the
 * feature extractor — it drives the service's single replace path.
 */
public record PersistApprovalPredictionCommand(
        UUID queryRequestId,
        Double probability,
        UUID modelId,
        Integer featureSchemaVersion,
        String featuresJson,
        boolean skipped,
        String skippedReason,
        boolean failed,
        String errorMessage) {
}
