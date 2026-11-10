package com.bablsoft.accessflow.deploygov.api;

/**
 * Post-deployment verification state of a deployment request, mirroring the
 * {@code verification_status} PG enum (#1023). {@code PENDING} until a verification starts,
 * {@code RUNNING} while samples are taken, then {@code PASSED} or {@code FAILED}; {@code SKIPPED}
 * when the request is never verified.
 */
public enum VerificationStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    SKIPPED
}
