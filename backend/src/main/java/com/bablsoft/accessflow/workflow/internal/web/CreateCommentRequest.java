package com.bablsoft.accessflow.workflow.internal.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to open a new inline comment thread anchored to a 1-based line range of the query's SQL.
 * Validation constraints are mirrored on the frontend comment form (validation parity rule).
 */
record CreateCommentRequest(
        @NotNull(message = "{validation.comment.anchor_start.required}")
        @Min(value = 1, message = "{validation.comment.anchor_start.min}")
        Integer anchorStartLine,

        @NotNull(message = "{validation.comment.anchor_end.required}")
        @Min(value = 1, message = "{validation.comment.anchor_end.min}")
        Integer anchorEndLine,

        @Size(max = 100_000, message = "{validation.comment.anchor_snapshot.max}")
        String anchorSnapshot,

        @NotBlank(message = "{validation.comment.body.required}")
        @Size(max = 4000, message = "{validation.comment.body.max}")
        String body) {
}
