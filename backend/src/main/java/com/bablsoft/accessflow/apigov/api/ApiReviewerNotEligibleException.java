package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/**
 * The caller may not decide this API request: they hold no {@code API_REQUEST_REVIEW} permission,
 * or the connector's review plan configures approver rules that neither their own identity nor any
 * identity borrowed through an out-of-office delegation matches (#622).
 *
 * <p>A connector with no review plan, or a plan with no approver rules, stays open to any holder of
 * the permission — see {@code DefaultApiReviewService.guardReviewable}.
 */
public final class ApiReviewerNotEligibleException extends RuntimeException {

    public ApiReviewerNotEligibleException(UUID reviewerId, UUID apiRequestId) {
        super("Reviewer " + reviewerId + " is not eligible to review API request " + apiRequestId);
    }
}
