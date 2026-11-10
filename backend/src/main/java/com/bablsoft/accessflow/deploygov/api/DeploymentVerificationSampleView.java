package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/** One stored post-deployment verification sample of a deployment request (#1023). */
public record DeploymentVerificationSampleView(
        UUID id,
        UUID deploymentRequestId,
        String checkName,
        VerificationCheckKind checkKind,
        Instant sampledAt,
        boolean passed,
        String observedValue,
        String detail) {
}
