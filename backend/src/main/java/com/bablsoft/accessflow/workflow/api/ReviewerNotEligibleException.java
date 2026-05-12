package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

public final class ReviewerNotEligibleException extends RuntimeException {

    public ReviewerNotEligibleException(UUID reviewerId, UUID queryRequestId) {
        super("Reviewer " + reviewerId
                + " is not eligible to review query " + queryRequestId + " at the current stage");
    }
}
