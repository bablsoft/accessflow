package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.constraints.Size;

/** Optional reviewer comment on an approve / revoke. */
public record AccessDecisionRequest(
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
