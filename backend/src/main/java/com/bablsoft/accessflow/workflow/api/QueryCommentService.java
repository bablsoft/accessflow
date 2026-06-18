package com.bablsoft.accessflow.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Inline collaboration comment threads anchored to a query's SQL while it is in a co-authorable
 * state. Every method authorizes the caller through {@link QueryCollaborationAccessService}
 * (submitter, eligible reviewer, or admin) and audits mutations. Out-of-scope / unknown queries
 * surface {@code QueryRequestNotFoundException}; missing comments surface
 * {@link QueryCommentNotFoundException}; non-collaborators / non-co-authorable queries surface
 * {@link CollaborationNotPermittedException}.
 */
public interface QueryCommentService {

    List<QueryCommentThreadView> listThreads(UUID queryRequestId, CollaboratorContext context);

    QueryCommentView addComment(UUID queryRequestId, CollaboratorContext context,
                                NewCommentInput input);

    QueryCommentView reply(UUID queryRequestId, UUID parentCommentId, CollaboratorContext context,
                           String body);

    QueryCommentView resolve(UUID queryRequestId, UUID commentId, CollaboratorContext context);

    QueryCommentView reopen(UUID queryRequestId, UUID commentId, CollaboratorContext context);
}
