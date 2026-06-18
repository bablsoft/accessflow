package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.CollaborationNotPermittedException;
import com.bablsoft.accessflow.workflow.api.CollaboratorContext;
import com.bablsoft.accessflow.workflow.api.CommentStatus;
import com.bablsoft.accessflow.workflow.api.NewCommentInput;
import com.bablsoft.accessflow.workflow.api.QueryCollaborationAccessService;
import com.bablsoft.accessflow.workflow.api.QueryCommentNotFoundException;
import com.bablsoft.accessflow.workflow.events.CommentChangeType;
import com.bablsoft.accessflow.workflow.events.QueryCommentChangedEvent;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryCommentEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryCommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQueryCommentServiceTest {

    private final QueryCommentRepository repository = org.mockito.Mockito.mock(QueryCommentRepository.class);
    private final QueryCollaborationAccessService accessService =
            org.mockito.Mockito.mock(QueryCollaborationAccessService.class);
    private final QueryRequestLookupService queryLookup =
            org.mockito.Mockito.mock(QueryRequestLookupService.class);
    private final UserQueryService userQuery = org.mockito.Mockito.mock(UserQueryService.class);
    private final ApplicationEventPublisher publisher =
            org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T10:00:00Z"), ZoneOffset.UTC);

    private final DefaultQueryCommentService service = new DefaultQueryCommentService(repository,
            accessService, queryLookup, userQuery, publisher, clock);

    private final UUID queryId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final CollaboratorContext context =
            new CollaboratorContext(authorId, orgId, UserRoleType.REVIEWER);

    @BeforeEach
    void setUp() {
        when(queryLookup.findById(queryId)).thenReturn(Optional.of(new QueryRequestSnapshot(
                queryId, UUID.randomUUID(), orgId, UUID.randomUUID(), "SELECT 1", QueryType.SELECT,
                false, QueryStatus.PENDING_REVIEW, null)));
        when(accessService.canCollaborate(any(), any(), any(), any())).thenReturn(true);
        when(accessService.collaboratorIds(any(), any())).thenReturn(Set.of(authorId));
        when(userQuery.findById(authorId))
                .thenReturn(Optional.of(user(authorId, "Ann")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void addCommentPersistsAndPublishesAddedEvent() {
        var view = service.addComment(queryId, context,
                new NewCommentInput(2, 4, "SELECT 1", "needs an index"));

        assertThat(view.body()).isEqualTo("needs an index");
        assertThat(view.anchorStartLine()).isEqualTo(2);
        assertThat(view.status()).isEqualTo(CommentStatus.OPEN);
        assertThat(view.author().displayName()).isEqualTo("Ann");
        assertThat(publishedEvent().changeType()).isEqualTo(CommentChangeType.ADDED);
    }

    @Test
    void addCommentNormalisesInvertedAnchorRange() {
        var view = service.addComment(queryId, context, new NewCommentInput(0, -3, null, "x"));
        assertThat(view.anchorStartLine()).isEqualTo(1);
        assertThat(view.anchorEndLine()).isEqualTo(1);
    }

    @Test
    void addCommentDeniedThrowsAndDoesNotPersist() {
        when(accessService.canCollaborate(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.addComment(queryId, context,
                new NewCommentInput(1, 1, null, "x")))
                .isInstanceOf(CollaborationNotPermittedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void addCommentUnknownQueryThrowsNotFound() {
        when(queryLookup.findById(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addComment(queryId, context,
                new NewCommentInput(1, 1, null, "x")))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void replyAttachesToRoot() {
        var rootId = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(rootId, queryId))
                .thenReturn(Optional.of(comment(rootId, null, CommentStatus.OPEN)));

        var view = service.reply(queryId, rootId, context, "agreed");

        assertThat(view.parentCommentId()).isEqualTo(rootId);
        assertThat(publishedEvent().changeType()).isEqualTo(CommentChangeType.REPLIED);
    }

    @Test
    void replyToAReplyFlattensToSharedRoot() {
        var rootId = UUID.randomUUID();
        var replyId = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(replyId, queryId))
                .thenReturn(Optional.of(comment(replyId, rootId, CommentStatus.OPEN)));
        when(repository.findByIdAndQueryRequestId(rootId, queryId))
                .thenReturn(Optional.of(comment(rootId, null, CommentStatus.OPEN)));

        var view = service.reply(queryId, replyId, context, "me too");

        assertThat(view.parentCommentId()).isEqualTo(rootId);
    }

    @Test
    void replyToMissingParentThrowsNotFound() {
        var missing = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(missing, queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reply(queryId, missing, context, "x"))
                .isInstanceOf(QueryCommentNotFoundException.class);
    }

    @Test
    void resolveSetsResolvedFieldsAndPublishes() {
        var rootId = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(rootId, queryId))
                .thenReturn(Optional.of(comment(rootId, null, CommentStatus.OPEN)));

        var view = service.resolve(queryId, rootId, context);

        assertThat(view.status()).isEqualTo(CommentStatus.RESOLVED);
        assertThat(view.resolvedBy().id()).isEqualTo(authorId);
        assertThat(view.resolvedAt()).isEqualTo(Instant.parse("2026-06-17T10:00:00Z"));
        assertThat(publishedEvent().changeType()).isEqualTo(CommentChangeType.RESOLVED);
    }

    @Test
    void resolveIsIdempotentWhenAlreadyResolved() {
        var rootId = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(rootId, queryId))
                .thenReturn(Optional.of(comment(rootId, null, CommentStatus.RESOLVED)));

        service.resolve(queryId, rootId, context);

        verify(repository, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void resolveOnReplyThrowsNotFound() {
        var replyId = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(replyId, queryId))
                .thenReturn(Optional.of(comment(replyId, UUID.randomUUID(), CommentStatus.OPEN)));

        assertThatThrownBy(() -> service.resolve(queryId, replyId, context))
                .isInstanceOf(QueryCommentNotFoundException.class);
    }

    @Test
    void reopenClearsResolutionAndPublishes() {
        var rootId = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(rootId, queryId))
                .thenReturn(Optional.of(comment(rootId, null, CommentStatus.RESOLVED)));

        var view = service.reopen(queryId, rootId, context);

        assertThat(view.status()).isEqualTo(CommentStatus.OPEN);
        assertThat(view.resolvedBy()).isNull();
        assertThat(publishedEvent().changeType()).isEqualTo(CommentChangeType.REOPENED);
    }

    @Test
    void reopenIsIdempotentWhenAlreadyOpen() {
        var rootId = UUID.randomUUID();
        when(repository.findByIdAndQueryRequestId(rootId, queryId))
                .thenReturn(Optional.of(comment(rootId, null, CommentStatus.OPEN)));

        service.reopen(queryId, rootId, context);

        verify(repository, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void listThreadsGroupsRootsWithReplies() {
        var rootId = UUID.randomUUID();
        var replyId = UUID.randomUUID();
        when(repository.findByQueryRequestIdOrderByCreatedAtAsc(queryId)).thenReturn(List.of(
                comment(rootId, null, CommentStatus.OPEN),
                comment(replyId, rootId, CommentStatus.OPEN)));

        var threads = service.listThreads(queryId, context);

        assertThat(threads).hasSize(1);
        assertThat(threads.get(0).root().id()).isEqualTo(rootId);
        assertThat(threads.get(0).replies()).hasSize(1);
        assertThat(threads.get(0).replies().get(0).id()).isEqualTo(replyId);
    }

    private QueryCommentChangedEvent publishedEvent() {
        var captor = ArgumentCaptor.forClass(QueryCommentChangedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private QueryCommentEntity comment(UUID id, UUID parentId, CommentStatus status) {
        var entity = new QueryCommentEntity();
        entity.setId(id);
        entity.setQueryRequestId(queryId);
        entity.setAuthorId(authorId);
        entity.setParentCommentId(parentId);
        entity.setAnchorStartLine(2);
        entity.setAnchorEndLine(3);
        entity.setBody("hello");
        entity.setStatus(status);
        entity.setCreatedAt(Instant.parse("2026-06-17T09:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-06-17T09:00:00Z"));
        return entity;
    }

    private UserView user(UUID id, String displayName) {
        return new UserView(id, displayName.toLowerCase() + "@example.com", displayName,
                UserRoleType.REVIEWER, orgId, true, null, null, null, null, false, Instant.now());
    }
}
