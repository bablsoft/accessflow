package com.bablsoft.accessflow.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for routing policies. Policies are evaluated by the workflow state machine after AI
 * analysis (first match by ascending {@code priority} wins). Priorities are unique per organization
 * so the ordering is deterministic; {@link #reorder} reassigns them atomically.
 */
public interface RoutingPolicyService {

    List<RoutingPolicyView> list(UUID organizationId);

    RoutingPolicyView get(UUID id, UUID organizationId);

    RoutingPolicyView create(CreateRoutingPolicyCommand command);

    RoutingPolicyView update(UUID id, UUID organizationId, UpdateRoutingPolicyCommand command);

    void delete(UUID id, UUID organizationId);

    /**
     * Reassign priorities so the policies appear in exactly {@code orderedIds} order (first id =
     * highest priority). The list must contain every policy id in the organization exactly once.
     *
     * @return the reordered policies.
     */
    List<RoutingPolicyView> reorder(UUID organizationId, List<UUID> orderedIds);
}
