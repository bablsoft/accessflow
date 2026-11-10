package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationSampleView;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;

import java.time.Instant;
import java.util.UUID;

/** One post-deployment verification sample on the wire (#1025). */
public record DeploymentVerificationSampleResponse(
        UUID id,
        UUID deploymentRequestId,
        String checkName,
        VerificationCheckKind checkKind,
        Instant sampledAt,
        boolean passed,
        String observedValue,
        String detail) {

    static DeploymentVerificationSampleResponse from(DeploymentVerificationSampleView view) {
        return new DeploymentVerificationSampleResponse(view.id(), view.deploymentRequestId(),
                view.checkName(), view.checkKind(), view.sampledAt(), view.passed(),
                view.observedValue(), view.detail());
    }
}
