package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;

/**
 * The read-time drift block of a version-inventory row (#742): how far the environment lags the
 * pipeline's newest successfully deployed version. {@code latestVersion}/{@code latestDeployedAt}
 * are null when the pipeline has no successful deployment at all; {@code daysBehind} and
 * {@code deploymentsBehind} are 0 on a non-drifted row and null when the row itself has no
 * {@code deployedAt} to measure from. Version comparison is plain string inequality — no semver.
 */
public record DeploymentVersionDriftView(
        String latestVersion,
        Instant latestDeployedAt,
        boolean drifted,
        Long daysBehind,
        Long deploymentsBehind) {
}
