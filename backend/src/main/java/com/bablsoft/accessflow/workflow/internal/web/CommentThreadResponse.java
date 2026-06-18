package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.QueryCommentThreadView;

import java.util.List;

/**
 * Web response for a comment thread: the root comment plus its replies.
 */
record CommentThreadResponse(CommentResponse root, List<CommentResponse> replies) {

    static CommentThreadResponse from(QueryCommentThreadView view) {
        return new CommentThreadResponse(
                CommentResponse.from(view.root()),
                view.replies().stream().map(CommentResponse::from).toList());
    }
}
