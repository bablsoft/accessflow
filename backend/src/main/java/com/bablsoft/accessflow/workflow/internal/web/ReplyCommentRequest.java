package com.bablsoft.accessflow.workflow.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to reply to an existing comment thread. Validation mirrored on the frontend.
 */
record ReplyCommentRequest(
        @NotBlank(message = "{validation.comment.body.required}")
        @Size(max = 4000, message = "{validation.comment.body.max}")
        String body) {
}
