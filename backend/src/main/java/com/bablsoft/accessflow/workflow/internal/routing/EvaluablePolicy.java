package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.RoutingAction;

import java.util.UUID;

/**
 * A routing policy reduced to what evaluation actually needs, so the engine can be run against a
 * set that is not simply "what is in the table" — the policy simulator (issue AF-630) evaluates the
 * org's current set and the set with a draft applied, and diffs the two.
 *
 * <p>{@code draft} marks the unsaved policy in the simulated set: it has no persisted id of its
 * own unless it replaces one, so callers cannot tell it apart by id alone.
 */
record EvaluablePolicy(UUID id, String name, int priority, RoutingAction action,
                       Integer requiredApprovals, String reason, ConditionNode condition,
                       boolean draft) {

    RoutingMatch toMatch() {
        return new RoutingMatch(id, name, action, requiredApprovals, reason);
    }
}
