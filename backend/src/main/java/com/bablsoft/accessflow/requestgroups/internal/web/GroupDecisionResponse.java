package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService.DecisionOutcome;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;

import java.util.UUID;

record GroupDecisionResponse(UUID decisionId, DecisionType decision, RequestGroupStatus resultingStatus,
                             boolean idempotentReplay) {

    static GroupDecisionResponse from(DecisionOutcome outcome) {
        return new GroupDecisionResponse(outcome.decisionId(), outcome.decision(),
                outcome.resultingStatus(), outcome.wasIdempotentReplay());
    }
}
