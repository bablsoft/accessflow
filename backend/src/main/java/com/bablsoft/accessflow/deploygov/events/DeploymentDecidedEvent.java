package com.bablsoft.accessflow.deploygov.events;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * A deployment reached an approve/reject verdict without a reviewer. {@code reason} carries the
 * provenance — {@code "routing:<policyId>"} for a routing-policy verdict, {@code "freeze:<windowId>"}
 * for a freeze-window auto-reject, {@code null} when the environment's own policy decided.
 */
public record DeploymentDecidedEvent(UUID deploymentRequestId, QueryStatus status, String reason) {
}
