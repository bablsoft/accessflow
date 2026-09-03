package com.bablsoft.accessflow.api.internal;

import java.time.Instant;

/**
 * Snapshot of the release update check. {@code currentVersion} is the running build's version
 * (null when build metadata is absent); {@code latestVersion} and {@code changelogUrl} come from
 * the published manifest and are null unless the last check succeeded; {@code checkedAt} is the
 * time of the last attempt, success or failure.
 */
public record UpdateStatusView(
        String currentVersion,
        String latestVersion,
        boolean updateAvailable,
        String changelogUrl,
        Instant checkedAt,
        UpdateCheckStatus status) {

    public static UpdateStatusView unknown(String currentVersion, Instant checkedAt) {
        return new UpdateStatusView(currentVersion, null, false, null, checkedAt, UpdateCheckStatus.UNKNOWN);
    }
}
