package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Resolved identity of a user permitted to join a query's collaboration room — returned by
 * {@link QueryCollaborationAccessService#resolveParticipant} so the realtime relay can label
 * presence without reaching into the user store itself.
 */
public record CollaboratorIdentity(UUID userId, String displayName) {
}
