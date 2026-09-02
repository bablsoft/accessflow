package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;

/**
 * The read-time drift block of a version-inventory row (#742): how far the environment lags the
 * pipeline's newest successfully deployed version. {@code latestVersion}/{@code latestDeployedAt}
 * are null when the pipeline has no qualifying latest (no deployment, or every tracker row's
 * outcome is FAILED/ROLLED_BACK) — a row with a non-null current version then still reports
 * {@code drifted = true}: the latest is unknown, so the row is conservatively flagged rather
 * than declared clean. {@code daysBehind} and {@code deploymentsBehind} are 0 on a non-drifted
 * row and null when the row has no {@code deployedAt} to measure from ({@code daysBehind} also
 * when no latest exists). Version comparison is plain string inequality — no semver.
 */
public record DeploymentVersionDriftView(
        String latestVersion,
        Instant latestDeployedAt,
        boolean drifted,
        Long daysBehind,
        Long deploymentsBehind) {
}
