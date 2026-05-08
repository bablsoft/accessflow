package com.partqam.accessflow.core.api;

public enum QueryStatus {
    PENDING_AI,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    TIMED_OUT,
    EXECUTED,
    FAILED,
    CANCELLED
}
