package com.bablsoft.accessflow.core.api;

/** Base of the review-delegation domain failures (#622). */
public sealed class ReviewDelegationException extends RuntimeException
        permits ReviewDelegationNotFoundException,
                IllegalReviewDelegationException {

    protected ReviewDelegationException(String message) {
        super(message);
    }
}
