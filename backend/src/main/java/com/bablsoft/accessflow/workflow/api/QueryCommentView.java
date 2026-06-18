package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A single inline collaboration comment. {@code parentCommentId} is {@code null} on a thread root;
 * {@code status} / {@code resolvedBy} / {@code resolvedAt} are meaningful only on the root.
 */
public record QueryCommentView(
        UUID id,
        UUID queryRequestId,
        UUID parentCommentId,
        CollaboratorRef author,
        int anchorStartLine,
        int anchorEndLine,
        String anchorSnapshot,
        String body,
        CommentStatus status,
        CollaboratorRef resolvedBy,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt) {
}
