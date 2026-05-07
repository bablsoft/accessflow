package com.partqam.accessflow.workflow.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewChangeRequestRequest(
        @NotBlank(message = "{validation.review_comment.max}")
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
