package com.bablsoft.accessflow.core.api;

import java.util.Objects;

/**
 * The bytes-scanned cap (#941) binding one user on one datasource: the most restrictive of the
 * datasource's {@code max_bytes_scanned_per_query} and the user's merged grant override, together
 * with the datasource's missing-estimate policy.
 */
public record AppliedBytesCap(long limit, BytesScannedCapSource source,
                              BytesCapMissingEstimateAction missingEstimate) {

    public AppliedBytesCap {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Objects.requireNonNull(source, "source");
        missingEstimate = missingEstimate == null
                ? BytesCapMissingEstimateAction.REQUIRE_REVIEW : missingEstimate;
    }

    /**
     * Compares an estimate against the cap. {@code null} means no estimate exists; the
     * datasource's missing-estimate policy then decides.
     */
    public BytesScannedCapOutcome check(Long estimatedBytesScanned) {
        if (estimatedBytesScanned == null) {
            return missingEstimate == BytesCapMissingEstimateAction.REJECT
                    ? BytesScannedCapOutcome.NO_ESTIMATE_REJECTED
                    : BytesScannedCapOutcome.NO_ESTIMATE_REVIEW;
        }
        return estimatedBytesScanned > limit
                ? BytesScannedCapOutcome.EXCEEDED
                : BytesScannedCapOutcome.WITHIN;
    }
}
