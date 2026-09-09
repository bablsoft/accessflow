package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * An unsaved routing policy, evaluated by the simulator (issue AF-630) as if it were persisted.
 * {@code replacesPolicyId} names the policy this draft would overwrite (the "edit" case); null
 * means the draft is a new policy inserted at its {@code priority}.
 *
 * <p>The condition arrives already decoded, so this type stays free of any JSON dependency.
 * Nothing here is ever persisted.
 */
public record RoutingPolicyDraft(UUID replacesPolicyId, String name, UUID datasourceId, int priority,
                                 boolean enabled, ConditionNode condition, RoutingAction action,
                                 Integer requiredApprovals, String reason) {
}
