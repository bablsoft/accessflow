package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a user's {@code active} flag transitions {@code true -> false}, regardless of the
 * initiating path (admin API update/delete, SCIM deprovisioning). Not published when deactivating
 * an already-inactive user.
 *
 * <p>Refresh-token revocation happens synchronously at the publishing service (a failed
 * revocation must surface to the caller); listeners own the remaining side-effects — the access
 * module revokes the user's active JIT grants.
 */
public record UserDeactivatedEvent(UUID userId, UUID organizationId) {
}
