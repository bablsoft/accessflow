package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.UpdateCheckStatus;
import com.bablsoft.accessflow.api.internal.UpdateStatusView;

import java.time.Instant;

record SystemUpdateStatusResponse(
        String currentVersion,
        String latestVersion,
        boolean updateAvailable,
        String changelogUrl,
        Instant checkedAt,
        UpdateCheckStatus status) {

    static SystemUpdateStatusResponse from(UpdateStatusView view) {
        return new SystemUpdateStatusResponse(
                view.currentVersion(),
                view.latestVersion(),
                view.updateAvailable(),
                view.changelogUrl(),
                view.checkedAt(),
                view.status());
    }
}
