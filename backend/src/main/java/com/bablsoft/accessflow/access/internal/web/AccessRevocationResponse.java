package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessReviewService.RevocationOutcome;

import java.util.UUID;

public record AccessRevocationResponse(
        UUID accessRequestId,
        AccessGrantStatus resultingStatus,
        boolean noOp) {

    public static AccessRevocationResponse from(UUID accessRequestId, RevocationOutcome outcome) {
        return new AccessRevocationResponse(accessRequestId, outcome.resultingStatus(),
                outcome.wasNoOp());
    }
}
