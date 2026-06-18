package com.bablsoft.accessflow.workflow.api;

/**
 * Input for creating a new thread-root comment anchored to a line range of the query's SQL.
 * {@code anchorSnapshot} is the SQL text of the anchored range at creation time (may be {@code null}).
 */
public record NewCommentInput(int anchorStartLine, int anchorEndLine, String anchorSnapshot,
                              String body) {
}
