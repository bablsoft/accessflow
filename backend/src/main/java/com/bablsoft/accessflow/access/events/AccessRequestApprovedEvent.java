package com.bablsoft.accessflow.access.events;

import java.util.UUID;

/** Published when an access-grant request reaches final-stage approval and is materialised. */
public record AccessRequestApprovedEvent(UUID accessRequestId, UUID reviewerId) {
}
