package com.bablsoft.accessflow.access.events;

import java.util.UUID;

/** Published when an admin early-revokes an active grant before its natural expiry. */
public record AccessGrantRevokedEvent(
        UUID accessRequestId,
        UUID requesterId,
        UUID permissionId,
        UUID revokedByUserId) {
}
