package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for "who may co-author / comment on this query." A user may collaborate
 * when the query exists in the caller's organization, is in a co-authorable state, and the caller is
 * the submitter, an eligible reviewer (review-plan approver in datasource scope), or an admin.
 *
 * <p>Co-authorable state: {@code PENDING_REVIEW} for anyone eligible; the submitter may also
 * collaborate while the query is still {@code PENDING_AI}. Used by both the comment service and the
 * realtime collaboration relay so the rules cannot drift.
 */
public interface QueryCollaborationAccessService {

    boolean canCollaborate(UUID queryRequestId, UUID userId, UUID organizationId, UserRoleType role);

    /**
     * Resolves the participant identity if the user may collaborate, otherwise empty. The realtime
     * relay calls this on {@code collab.join}.
     */
    Optional<CollaboratorIdentity> resolveParticipant(UUID queryRequestId, UUID userId,
                                                       UUID organizationId, UserRoleType role);

    /**
     * All users who may collaborate on the query (submitter + active eligible reviewers), used as
     * the recipient set for realtime comment broadcasts. Empty if the query is unknown or outside
     * the organization.
     */
    Set<UUID> collaboratorIds(UUID queryRequestId, UUID organizationId);
}
