package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.CollaboratorRef;
import com.bablsoft.accessflow.workflow.api.CommentStatus;
import com.bablsoft.accessflow.workflow.api.QueryCommentThreadView;
import com.bablsoft.accessflow.workflow.api.QueryCommentView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommentResponseTest {

    @Test
    void mapsAllFieldsIncludingResolver() {
        var view = root(CommentStatus.RESOLVED,
                new CollaboratorRef(UUID.randomUUID(), "Rev", "rev@example.com"));

        var response = CommentResponse.from(view);

        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.author().displayName()).isEqualTo("Ann");
        assertThat(response.status()).isEqualTo(CommentStatus.RESOLVED);
        assertThat(response.resolvedBy().displayName()).isEqualTo("Rev");
        assertThat(response.anchorStartLine()).isEqualTo(2);
    }

    @Test
    void nullResolverMapsToNull() {
        var response = CommentResponse.from(root(CommentStatus.OPEN, null));
        assertThat(response.resolvedBy()).isNull();
    }

    @Test
    void threadResponseMapsRootAndReplies() {
        var thread = new QueryCommentThreadView(root(CommentStatus.OPEN, null),
                List.of(reply()));

        var response = CommentThreadResponse.from(thread);

        assertThat(response.root().body()).isEqualTo("root");
        assertThat(response.replies()).hasSize(1);
        assertThat(response.replies().get(0).body()).isEqualTo("reply");
    }

    private static QueryCommentView root(CommentStatus status, CollaboratorRef resolvedBy) {
        return new QueryCommentView(UUID.randomUUID(), UUID.randomUUID(), null,
                new CollaboratorRef(UUID.randomUUID(), "Ann", "ann@example.com"),
                2, 4, "SELECT 1", "root", status, resolvedBy,
                status == CommentStatus.RESOLVED ? Instant.now() : null,
                Instant.now(), Instant.now());
    }

    private static QueryCommentView reply() {
        var rootId = UUID.randomUUID();
        return new QueryCommentView(UUID.randomUUID(), UUID.randomUUID(), rootId,
                new CollaboratorRef(UUID.randomUUID(), "Bob", "bob@example.com"),
                2, 4, null, "reply", CommentStatus.OPEN, null, null,
                Instant.now(), Instant.now());
    }
}
