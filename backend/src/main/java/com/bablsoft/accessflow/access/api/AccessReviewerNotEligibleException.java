package com.bablsoft.accessflow.access.api;

import java.util.UUID;

public final class AccessReviewerNotEligibleException extends AccessException {

    public AccessReviewerNotEligibleException(UUID reviewerId, UUID accessRequestId) {
        super("Reviewer " + reviewerId + " is not eligible to review access request "
                + accessRequestId + " at the current stage");
    }
}
