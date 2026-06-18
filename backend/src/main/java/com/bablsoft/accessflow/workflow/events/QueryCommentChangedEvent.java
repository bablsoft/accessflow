package com.bablsoft.accessflow.workflow.events;

import java.util.Set;
import java.util.UUID;

/**
 * Published whenever an inline collaboration comment thread changes (created, replied, resolved,
 * reopened). The realtime module fans a {@code collab.comment} frame to the query's collaborators so
 * their comment panels refetch. {@code recipientIds} is the set of users who should be notified
 * (submitter + eligible reviewers), computed at publish time.
 */
public record QueryCommentChangedEvent(
        UUID queryRequestId,
        UUID commentId,
        CommentChangeType changeType,
        UUID actorId,
        Set<UUID> recipientIds) {
}
