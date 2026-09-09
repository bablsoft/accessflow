package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.RoutingAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Dry-run request for a draft routing policy (issue AF-630): the same body a create takes, wrapped
 * in the window to replay and an optional {@code replacesPolicyId} for the "edit an existing
 * policy" case.
 *
 * <p>{@code datasourceId} at the top level narrows the <em>corpus</em>; {@code draft.datasourceId}
 * is the draft policy's own scope. Keeping them separate is deliberate — an org-wide draft is a
 * legitimate thing to test against one datasource's traffic.
 */
public record SimulateRoutingPolicyRequest(
        @NotNull(message = "{validation.simulation_period.required}")
        Instant from,

        @NotNull(message = "{validation.simulation_period.required}")
        Instant to,

        UUID datasourceId,

        @NotNull(message = "{validation.simulation_draft.required}")
        @Valid
        Draft draft
) {

    /** The unsaved policy, carrying every constraint its persisted counterpart carries. */
    public record Draft(
            UUID replacesPolicyId,

            @NotBlank(message = "{validation.routing_policy_name.required}")
            @Size(max = 255, message = "{validation.routing_policy_name.size}")
            String name,

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
}
