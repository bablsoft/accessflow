package com.bablsoft.accessflow.workflow.events;

import com.bablsoft.accessflow.core.api.DecisionType;

import java.util.UUID;

/**
 * Published whenever a reviewer makes a decision on a query — approve, reject, or request
 * changes. Unlike the existing {@code QueryApprovedEvent}/{@code QueryRejectedEvent} pair,
 * this fires on every non-replay decision (not only the terminal one) and includes the
 * comment, so the realtime channel can echo the decision to the submitter immediately.
 */
public record ReviewDecisionMadeEvent(
        UUID queryRequestId,
        UUID submitterId,
        UUID reviewerId,
        DecisionType decision,
        String comment) {
}
