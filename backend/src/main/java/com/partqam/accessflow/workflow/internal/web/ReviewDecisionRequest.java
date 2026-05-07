package com.partqam.accessflow.workflow.internal.web;

import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
