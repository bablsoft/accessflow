package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query is auto-approved by the state machine on AI completion. When a routing
 * policy drove the decision, {@code matchedPolicyId} and {@code reason} carry its provenance; both
 * are {@code null} for the legacy fast-path (because {@code requires_human_approval=false} or the
 * {@code auto_approve_reads} fast-path applied).
 */
public record QueryAutoApprovedEvent(UUID queryRequestId, UUID matchedPolicyId, String reason) {

    public QueryAutoApprovedEvent(UUID queryRequestId) {
        this(queryRequestId, null, null);
    }
}
