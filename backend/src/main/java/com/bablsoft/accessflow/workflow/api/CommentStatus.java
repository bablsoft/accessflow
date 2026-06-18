package com.bablsoft.accessflow.workflow.api;

/**
 * Lifecycle of an inline collaboration comment thread. Meaningful only on the root comment of a
 * thread; replies inherit the root's status implicitly.
 */
public enum CommentStatus {
    OPEN,
    RESOLVED
}
