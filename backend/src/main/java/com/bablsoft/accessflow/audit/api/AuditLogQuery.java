package com.bablsoft.accessflow.audit.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter for {@link AuditLogService#query}. All fields are optional; null means
 * "no filter on this field". {@code onBehalfOfUserId} (#875) matches the
 * {@code metadata.on_behalf_of_user_id} key an API-key caller stamps when it acts for a named
 * person (#874) — the rows a human is attributed on without being the actor.
 */
public record AuditLogQuery(
        UUID actorId,
        AuditAction action,
        AuditResourceType resourceType,
        UUID resourceId,
        Instant from,
        Instant to,
        UUID onBehalfOfUserId) {

    /** Legacy shape without the on-behalf-of filter (#875). */
    public AuditLogQuery(UUID actorId, AuditAction action, AuditResourceType resourceType, UUID resourceId,
                         Instant from, Instant to) {
        this(actorId, action, resourceType, resourceId, from, to, null);
    }

    public static AuditLogQuery empty() {
        return new AuditLogQuery(null, null, null, null, null, null, null);
    }
}
