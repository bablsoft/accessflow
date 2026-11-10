package com.bablsoft.accessflow.deploygov.api;

import java.time.Duration;
import java.util.List;

/**
 * An environment's post-deployment verification config (#1023): sample every
 * {@code sampleInterval} for {@code window}, failing once a check has failed
 * {@code failureThreshold} samples.
 */
public record DeploymentVerificationConfig(Duration window, Duration sampleInterval,
                                           int failureThreshold, List<VerificationCheck> checks) {

    public DeploymentVerificationConfig {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
