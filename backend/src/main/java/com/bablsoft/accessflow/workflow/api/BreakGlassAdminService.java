package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Admin "Break-glass log" (AF-385): reads the retro-review events and acknowledges (reconciles)
 * them. Reading is open to ADMIN/AUDITOR; acknowledging is ADMIN-only, enforced at the web layer.
 */
public interface BreakGlassAdminService {

    PageResponse<BreakGlassEventView> list(UUID organizationId, BreakGlassEventFilter filter,
                                           PageRequest pageRequest);

    BreakGlassEventView get(UUID organizationId, UUID eventId);

    /**
     * Reconciles a {@link BreakGlassStatus#PENDING_REVIEW} event to {@link BreakGlassStatus#REVIEWED}.
     *
     * @throws BreakGlassEventNotFoundException if no event exists in this organization.
     * @throws BreakGlassAlreadyReviewedException if the event is already reviewed.
     * @throws SelfAcknowledgeNotAllowedException if the actor is the submitter of the break-glass query.
     */
    BreakGlassEventView acknowledge(UUID organizationId, UUID eventId, UUID actorUserId,
                                    String comment);
}
