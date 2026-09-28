package com.bablsoft.accessflow.workflow.api;

/** Who decided a query's routing decision (#945). */
public enum RoutingDecisionSource {

    /** A local routing policy matched. */
    POLICY,

    /** No policy matched and the external decision hook escalated, required approvals or rejected. */
    DECISION_HOOK
}
