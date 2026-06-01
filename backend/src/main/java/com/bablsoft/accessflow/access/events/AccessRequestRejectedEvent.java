package com.bablsoft.accessflow.access.events;

import java.util.UUID;

/** Published when a reviewer rejects an access-grant request. The requester is notified. */
public record AccessRequestRejectedEvent(UUID accessRequestId, UUID reviewerId) {
}
