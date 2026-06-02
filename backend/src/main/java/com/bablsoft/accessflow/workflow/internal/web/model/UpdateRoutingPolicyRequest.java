package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.RoutingAction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Full-replace update request for a routing policy (PUT semantics). Mirrors
 * {@link CreateRoutingPolicyRequest}.
 */
public record UpdateRoutingPolicyRequest(
        @NotBlank(message = "{validation.routing_policy_name.required}")
        @Size(max = 255, message = "{validation.routing_policy_name.size}")
        String name,

        @Size(max = 2000, message = "{validation.routing_policy_description.max}")
        String description,

        UUID datasourceId,

        @Min(value = 0, message = "{validation.routing_policy_priority.min}")
        int priority,

        Boolean enabled,

        @NotNull(message = "{validation.routing_policy_condition.required}")
        JsonNode condition,

        @NotNull(message = "{validation.routing_policy_action.required}")
        RoutingAction action,

        @Min(value = 1, message = "{validation.routing_policy_approvals.min}")
        Integer requiredApprovals,

        @Size(max = 500, message = "{validation.routing_policy_reason.max}")
        String reason
) {}
