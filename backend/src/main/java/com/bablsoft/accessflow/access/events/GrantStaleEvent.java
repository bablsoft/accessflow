package com.bablsoft.accessflow.access.events;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.core.api.GrantResourceKind;

import java.util.UUID;

/**
 * A standing grant has crossed the staleness threshold (#625) — published once per grant per
 * {@code accessflow.access.usage.nudge-cooldown} so the notification is a nudge, not a drip.
 *
 * <p>Addressed to organization <strong>administrators</strong>, not to the grant holder: admins are
 * the party who can act on it, and telling one user about another's inactivity would leak activity
 * data across the tenant. Purely advisory — nothing revokes anything on the strength of this event.
 *
 * <p>{@code daysSinceLastUse} is null when the grant has never been used, which the notification
 * renders differently from "idle for N days".
 */
public record GrantStaleEvent(
        UUID organizationId,
        UUID summaryId,
        UUID userId,
        String userEmail,
        GrantResourceKind resourceKind,
        UUID resourceId,
        String resourceName,
        Long daysSinceLastUse,
        GrantUsageRecommendation recommendation) {
}
