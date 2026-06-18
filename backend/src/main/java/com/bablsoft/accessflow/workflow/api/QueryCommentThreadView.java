package com.bablsoft.accessflow.workflow.api;

import java.util.List;

/**
 * A comment thread: the root comment plus its replies in chronological order.
 */
public record QueryCommentThreadView(QueryCommentView root, List<QueryCommentView> replies) {
}
