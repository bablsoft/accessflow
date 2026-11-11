package com.bablsoft.accessflow.deploygov.api;

import java.time.Duration;

/**
 * The verification config a request was verified against (#1027), as snapshotted when its window
 * opened. Deliberately carries no check URLs or queries: the request detail is readable by its
 * submitter and reviewers, while an environment's config is admin-only.
 */
public record DeploymentVerificationSnapshotView(Duration window, Duration sampleInterval,
                                                 int failureThreshold, int checkCount) {

    public static DeploymentVerificationSnapshotView of(DeploymentVerificationConfig config) {
        return config == null ? null
                : new DeploymentVerificationSnapshotView(config.window(), config.sampleInterval(),
                        config.failureThreshold(), config.checks().size());
    }
}
