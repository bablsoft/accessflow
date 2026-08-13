package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a user's {@code active} flag transitions {@code true -> false}, regardless of the
 * initiating path (admin API update/delete, SCIM deprovisioning). Not published when deactivating
 * an already-inactive user.
 *
 * <p>Consumers own the deactivation side-effects: the security module revokes all refresh tokens,
 * the access module revokes the user's active JIT grants.
 */
public record UserDeactivatedEvent(UUID userId, UUID organizationId) {
}
