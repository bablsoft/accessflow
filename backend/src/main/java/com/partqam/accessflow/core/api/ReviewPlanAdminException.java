package com.partqam.accessflow.core.api;

public sealed class ReviewPlanAdminException extends RuntimeException
        permits ReviewPlanNotFoundException,
                ReviewPlanInUseException,
                ReviewPlanNameAlreadyExistsException,
                IllegalReviewPlanException {

    protected ReviewPlanAdminException(String message) {
        super(message);
    }
}
