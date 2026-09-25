package com.bablsoft.accessflow.core.api;

/**
 * The bytes-scanned cap (#941) refused a statement just before execution — its pre-flight
 * estimate exceeds the cap, or it has none and the datasource rejects on a missing estimate. The
 * message is the caller's localized explanation naming the estimate and the limit; execution paths
 * record it as the failure reason.
 */
public class BytesScannedCapExceededException extends RuntimeException {

    private final transient AppliedBytesCap cap;
    private final Long estimatedBytes;
    private final BytesScannedCapOutcome outcome;

    public BytesScannedCapExceededException(String message, AppliedBytesCap cap,
                                            Long estimatedBytes, BytesScannedCapOutcome outcome) {
        super(message);
        this.cap = cap;
        this.estimatedBytes = estimatedBytes;
        this.outcome = outcome;
    }

    public AppliedBytesCap cap() {
        return cap;
    }

    public Long estimatedBytes() {
        return estimatedBytes;
    }

    public BytesScannedCapOutcome outcome() {
        return outcome;
    }
}
