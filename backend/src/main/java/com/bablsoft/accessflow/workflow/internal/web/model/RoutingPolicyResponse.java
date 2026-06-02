package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyView;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * API response for a routing policy. {@code condition} is rendered back to the typed JSON wire shape
 * by the controller (via the codec) so the frontend round-trips it through the guided builder.
 */
public record RoutingPolicyResponse(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String name,
        String description,
        int priority,
        boolean enabled,
        JsonNode condition,
        RoutingAction action,
        Integer requiredApprovals,
        String reason,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static RoutingPolicyResponse from(RoutingPolicyView view, JsonNode condition) {
        return new RoutingPolicyResponse(
                view.id(),
                view.organizationId(),
                view.datasourceId(),
                view.name(),
                view.description(),
                view.priority(),
                view.enabled(),
                condition,
                view.action(),
                view.requiredApprovals(),
                view.reason(),
                view.version(),
                view.createdAt(),
                view.updatedAt());
    }
}
