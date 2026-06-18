package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when no comment matches the given id within the given query. Maps to HTTP 404.
 */
public final class QueryCommentNotFoundException extends RuntimeException {

    private final UUID commentId;

    public QueryCommentNotFoundException(UUID commentId) {
        super("Query comment not found: " + commentId);
        this.commentId = commentId;
    }

    public UUID commentId() {
        return commentId;
    }
}
