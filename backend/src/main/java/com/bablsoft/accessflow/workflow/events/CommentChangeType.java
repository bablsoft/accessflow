package com.bablsoft.accessflow.workflow.events;

/**
 * The kind of change carried by a {@link QueryCommentChangedEvent}, so realtime subscribers can
 * tailor their reaction (all currently trigger a comment refetch).
 */
public enum CommentChangeType {
    ADDED,
    REPLIED,
    RESOLVED,
    REOPENED
}
