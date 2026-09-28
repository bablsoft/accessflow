package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query is rejected without a reviewer: a routing policy matched with the
 * {@code AUTO_REJECT} action (carrying its {@code routing_policy.id}), the external decision hook
 * rejected it (carrying the hook id, #945), the bytes-scanned cap refused it (#941), or an external
 * ticket resolution rejected it — the last two with null ids. The reason lets the audit and
 * notification listeners record provenance.
 */
public record QueryAutoRejectedEvent(UUID queryRequestId, UUID matchedPolicyId, String reason,
                                     UUID decisionHookId) {

    /** A rejection the decision hook took no part in. */
    public QueryAutoRejectedEvent(UUID queryRequestId, UUID matchedPolicyId, String reason) {
        this(queryRequestId, matchedPolicyId, reason, null);
    }
}
