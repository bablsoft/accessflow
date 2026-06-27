package com.bablsoft.accessflow.apigov.api;

/**
 * Action an API routing policy applies when its conditions match a submitted call. Mirrors the
 * query-side routing actions: {@code AUTO_APPROVE} / {@code AUTO_REJECT} skip human review,
 * {@code REQUIRE_APPROVALS} routes to review with an explicit approval count, {@code ESCALATE}
 * routes to review with the plan's count bumped by the policy's count.
 */
public enum ApiRoutingAction {
    AUTO_APPROVE,
    AUTO_REJECT,
    REQUIRE_APPROVALS,
    ESCALATE
}
