package com.bablsoft.accessflow.lifecycle.internal.web;

import jakarta.validation.constraints.Size;

/** Optional reviewer comment on an erasure approve / reject. */
public record ErasureDecisionRequest(
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
