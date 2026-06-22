package com.bablsoft.accessflow.workflow.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A review decision committed from a one-tap push action (AF-444). Only approve/reject are
 * available from push (request-changes stays an in-app action). The {@code stepUpToken} is the
 * single-use token minted by {@code POST /auth/step-up}; it proves the user re-verified before the
 * decision commits. The self-approval and reviewer-eligibility guards still apply server-side.
 */
public record PushDecisionRequest(
        @NotNull(message = "{validation.push_decision.required}") PushDecisionType decision,
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment,
        @NotBlank(message = "{validation.step_up_token.required}") String stepUpToken) {

    public enum PushDecisionType {
        APPROVE,
        REJECT
    }
}
