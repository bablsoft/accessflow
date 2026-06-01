package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reviewer comment on a rejection (required). */
public record AccessRejectRequest(
        @NotBlank(message = "{validation.review_comment.required}")
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
