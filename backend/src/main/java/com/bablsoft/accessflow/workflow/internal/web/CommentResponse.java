package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.CollaboratorRef;
import com.bablsoft.accessflow.workflow.api.CommentStatus;
import com.bablsoft.accessflow.workflow.api.QueryCommentView;

import java.time.Instant;
import java.util.UUID;

/**
 * Web response for a single inline comment. {@code parentCommentId} is null on a thread root;
 * {@code status} / {@code resolvedBy} / {@code resolvedAt} are meaningful only on the root.
 */
record CommentResponse(
        UUID id,
        UUID queryRequestId,
        UUID parentCommentId,
        UserRef author,
        int anchorStartLine,
        int anchorEndLine,
        String anchorSnapshot,
        String body,
        CommentStatus status,
        UserRef resolvedBy,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt) {

    record UserRef(UUID id, String displayName, String email) {
        static UserRef from(CollaboratorRef ref) {
            return ref == null ? null : new UserRef(ref.id(), ref.displayName(), ref.email());
        }
    }

    static CommentResponse from(QueryCommentView view) {
        return new CommentResponse(
                view.id(),
                view.queryRequestId(),
                view.parentCommentId(),
                UserRef.from(view.author()),
                view.anchorStartLine(),
                view.anchorEndLine(),
                view.anchorSnapshot(),
                view.body(),
                view.status(),
                UserRef.from(view.resolvedBy()),
                view.resolvedAt(),
                view.createdAt(),
                view.updatedAt());
    }
}
