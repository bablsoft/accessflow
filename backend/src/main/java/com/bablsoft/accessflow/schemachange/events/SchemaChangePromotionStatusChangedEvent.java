package com.bablsoft.accessflow.schemachange.events;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;

import java.util.UUID;

/**
 * Published on every promotion status change (#880) — from the promotion service on submission
 * ({@code oldStatus} null) and cancellation, and from the status projection listener as the
 * request group moves. Notifications (#882) and realtime hang off this rather than off the
 * group's own events, which carry no promotion context.
 */
public record SchemaChangePromotionStatusChangedEvent(
        UUID promotionId,
        UUID changeSetId,
        UUID environmentId,
        UUID organizationId,
        UUID promotedBy,
        SchemaChangePromotionStatus oldStatus,
        SchemaChangePromotionStatus newStatus) {
}
