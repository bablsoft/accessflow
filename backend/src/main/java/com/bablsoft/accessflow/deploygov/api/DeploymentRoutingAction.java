package com.bablsoft.accessflow.deploygov.api;

/**
 * Action a deployment routing policy applies when its conditions match a submitted deployment.
 * Mirrors the query/API-side routing actions and maps the shared {@code api_routing_action}
 * PostgreSQL enum with a module-local Java type (its apigov sibling maps the same PG type as
 * {@code ApiRoutingAction}): {@code AUTO_APPROVE} / {@code AUTO_REJECT} skip human review,
 * {@code REQUIRE_APPROVALS} routes to review with an explicit approval count, {@code ESCALATE}
 * routes to review with the plan's count bumped by the policy's count.
 */
public enum DeploymentRoutingAction {
    AUTO_APPROVE,
    AUTO_REJECT,
    REQUIRE_APPROVALS,
    ESCALATE
}
