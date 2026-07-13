package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query is auto-approved by the state machine on AI completion. When a routing
 * policy drove the decision, {@code matchedPolicyId} and {@code reason} carry its provenance; when
 * a grant-covered fast-path drove it (#582), {@code accessGrantId} and {@code grantApproverEmail}
 * carry the grant lineage instead. All four are {@code null} for the legacy fast-path (because
 * {@code requires_human_approval=false} or the {@code auto_approve_reads} fast-path applied).
 */
public record QueryAutoApprovedEvent(UUID queryRequestId, UUID matchedPolicyId, String reason,
                                     UUID accessGrantId, String grantApproverEmail) {

    public QueryAutoApprovedEvent(UUID queryRequestId) {
        this(queryRequestId, null, null, null, null);
    }

    public QueryAutoApprovedEvent(UUID queryRequestId, UUID matchedPolicyId, String reason) {
        this(queryRequestId, matchedPolicyId, reason, null, null);
    }
}
