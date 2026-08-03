package com.bablsoft.accessflow.ai.internal;

/**
 * Thrown when a {@link TrainedApprovalModel} cannot be serialized to or deserialized from JSON —
 * for example when a stored model row is corrupt or the schema version is unrecognized.
 * Callers (the retraining job, the serving path) should catch this and record a {@code failed}
 * sentinel rather than propagating an anonymous {@link RuntimeException}.
 */
public class ApprovalModelSerializationException extends RuntimeException {

    public ApprovalModelSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
