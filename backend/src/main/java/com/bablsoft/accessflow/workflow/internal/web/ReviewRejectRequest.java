package com.bablsoft.accessflow.workflow.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewRejectRequest(
        @NotBlank(message = "{validation.review_comment.required}")
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
