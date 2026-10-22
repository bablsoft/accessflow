package com.bablsoft.accessflow.deploygov.events;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * A deployment reached an approve/reject verdict without a reviewer. {@code reason} carries the
 * provenance — {@code "routing:<policyId>"} for a routing-policy verdict, {@code "freeze:<windowId>"}
 * for a freeze-window auto-reject, {@code "review_timeout"} for a timeout,
 * {@code "ticket:<SYSTEM>:<external_key>"} for an external ticket resolution (#992), {@code null}
 * when the environment's own policy decided.
 */
public record DeploymentDecidedEvent(UUID deploymentRequestId, QueryStatus status, String reason) {
}
