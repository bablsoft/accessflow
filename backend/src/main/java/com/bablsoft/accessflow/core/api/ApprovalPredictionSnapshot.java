package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module DTO carrying a persisted approval-outcome prediction (issue AF-645) — the
 * {@code approval_predictions} row written when a query lands in review. {@code probability} is
 * {@code null} on the {@code skipped} ("not enough history yet") and {@code failed} sentinel rows;
 * {@code featuresJson} snapshots the serving-time feature vector for explainability.
 */
public record ApprovalPredictionSnapshot(
        UUID id,
        UUID queryRequestId,
        Double probability,
        UUID modelId,
        Integer featureSchemaVersion,
        String featuresJson,
        boolean skipped,
        String skippedReason,
        boolean failed,
        String errorMessage,
        Instant createdAt) {
}
