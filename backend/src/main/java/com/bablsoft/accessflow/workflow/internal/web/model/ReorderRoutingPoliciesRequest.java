package com.bablsoft.accessflow.workflow.internal.web.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Reorder request — the full list of policy ids in the desired priority order (first = highest
 * priority). Must contain every policy in the organization exactly once.
 */
public record ReorderRoutingPoliciesRequest(
        @NotEmpty(message = "{validation.routing_policy_order.required}")
        List<@NotNull UUID> orderedIds
) {}
