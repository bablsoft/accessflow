package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.AppliedBytesCap;
import com.bablsoft.accessflow.core.api.BytesScannedCapOutcome;
import com.bablsoft.accessflow.core.api.BytesScannedCapSource;

import java.util.Objects;

/**
 * The bytes-scanned cap (#941) that applies to one request, and how its estimate compared — the
 * input {@link QueryDecisionEvaluator} decides on, like the {@code BLOCK} rule ids.
 *
 * @param estimatedBytes the persisted pre-flight estimate, {@code null} when there is none
 * @param outcome        the comparison, or {@code null} when it cannot be made at all: the access
 *                       simulator has no submitted query and therefore no estimate, so it reports
 *                       the cap without letting a guessed outcome decide
 */
record BytesCapCheck(long limit, BytesScannedCapSource source, Long estimatedBytes,
                     BytesScannedCapOutcome outcome) {

    BytesCapCheck {
        Objects.requireNonNull(source, "source");
    }

    static BytesCapCheck of(AppliedBytesCap cap, Long estimatedBytes) {
        return new BytesCapCheck(cap.limit(), cap.source(), estimatedBytes,
                cap.check(estimatedBytes));
    }

    /** A cap the caller knows applies but cannot compare (the simulator). */
    static BytesCapCheck unevaluated(AppliedBytesCap cap) {
        return new BytesCapCheck(cap.limit(), cap.source(), null, null);
    }

    boolean rejects() {
        return outcome != null && outcome.rejects();
    }

    boolean forcesReview() {
        return outcome == BytesScannedCapOutcome.NO_ESTIMATE_REVIEW;
    }
}
