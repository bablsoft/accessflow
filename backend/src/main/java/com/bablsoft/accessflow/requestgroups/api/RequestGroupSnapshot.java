package com.bablsoft.accessflow.requestgroups.api;

import java.util.UUID;

/** Lightweight cross-module read of a group's identity + ownership (used by notifications/audit). */
public record RequestGroupSnapshot(
        UUID id,
        UUID organizationId,
        UUID submittedByUserId,
        String name,
        RequestGroupStatus status) {
}
