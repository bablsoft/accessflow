package com.bablsoft.accessflow.access.events;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;

import java.util.UUID;

/** Published on every access-request status transition; drives the generic realtime status push. */
public record AccessRequestStatusChangedEvent(
        UUID accessRequestId,
        UUID requesterId,
        AccessGrantStatus oldStatus,
        AccessGrantStatus newStatus) {
}
