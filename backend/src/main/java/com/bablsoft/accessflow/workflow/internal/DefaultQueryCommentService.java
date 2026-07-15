package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.CollaborationNotPermittedException;
import com.bablsoft.accessflow.workflow.api.CollaboratorContext;
import com.bablsoft.accessflow.workflow.api.CollaboratorRef;
import com.bablsoft.accessflow.workflow.api.CommentStatus;
import com.bablsoft.accessflow.workflow.api.NewCommentInput;
import com.bablsoft.accessflow.workflow.api.QueryCollaborationAccessService;
import com.bablsoft.accessflow.workflow.api.QueryCommentNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryCommentService;
import com.bablsoft.accessflow.workflow.api.QueryCommentThreadView;
import com.bablsoft.accessflow.workflow.api.QueryCommentView;
import com.bablsoft.accessflow.workflow.events.CommentChangeType;
import com.bablsoft.accessflow.workflow.events.QueryCommentChangedEvent;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryCommentEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryCommentService implements QueryCommentService {

    private final QueryCommentRepository commentRepository;
    private final QueryCollaborationAccessService collaborationAccessService;
    private final QueryRequestLookupService queryRequestLookupService;
    private final UserQueryService userQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<QueryCommentThreadView> listThreads(UUID queryRequestId,
                                                    CollaboratorContext context) {
        authorize(queryRequestId, context);
        var all = commentRepository.findByQueryRequestIdOrderByCreatedAtAsc(queryRequestId);
        var users = loadUsers(all);
        var repliesByRoot = new LinkedHashMap<UUID, List<QueryCommentView>>();
        var roots = new ArrayList<QueryCommentEntity>();
        for (var entity : all) {
            if (entity.getParentCommentId() == null) {
                roots.add(entity);
            } else {
                repliesByRoot.computeIfAbsent(entity.getParentCommentId(), k -> new ArrayList<>())
                        .add(toView(entity, users));
            }
        }
        var threads = new ArrayList<QueryCommentThreadView>(roots.size());
        for (var root : roots) {
            threads.add(new QueryCommentThreadView(toView(root, users),
                    repliesByRoot.getOrDefault(root.getId(), List.of())));
        }
        return threads;
    }

    @Override
    @Transactional
    public QueryCommentView addComment(UUID queryRequestId, CollaboratorContext context,
                                       NewCommentInput input) {
        authorize(queryRequestId, context);
        var startLine = Math.max(1, input.anchorStartLine());
        var endLine = Math.max(startLine, input.anchorEndLine());
        var entity = new QueryCommentEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(queryRequestId);
        entity.setAuthorId(context.userId());
        entity.setAnchorStartLine(startLine);
        entity.setAnchorEndLine(endLine);
        entity.setAnchorSnapshot(input.anchorSnapshot());
        entity.setBody(input.body());
        entity.setStatus(CommentStatus.OPEN);
        var saved = commentRepository.save(entity);
        publish(queryRequestId, saved.getId(), CommentChangeType.ADDED, context);
        return toView(saved, loadUsers(List.of(saved)));
    }

    @Override
    @Transactional
    public QueryCommentView reply(UUID queryRequestId, UUID parentCommentId,
                                  CollaboratorContext context, String body) {
        authorize(queryRequestId, context);
        var parent = commentRepository.findByIdAndQueryRequestId(parentCommentId, queryRequestId)
                .orElseThrow(() -> new QueryCommentNotFoundException(parentCommentId));
        // Flatten to a two-level tree: a reply to a reply attaches to the shared root.
        var root = parent.getParentCommentId() == null
                ? parent
                : commentRepository.findByIdAndQueryRequestId(parent.getParentCommentId(),
                        queryRequestId).orElse(parent);
        var entity = new QueryCommentEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(queryRequestId);
        entity.setAuthorId(context.userId());
        entity.setParentCommentId(root.getId());
        entity.setAnchorStartLine(root.getAnchorStartLine());
        entity.setAnchorEndLine(root.getAnchorEndLine());
        entity.setBody(body);
        entity.setStatus(CommentStatus.OPEN);
        var saved = commentRepository.save(entity);
        publish(queryRequestId, saved.getId(), CommentChangeType.REPLIED, context);
        return toView(saved, loadUsers(List.of(saved)));
    }

    @Override
    @Transactional
    public QueryCommentView resolve(UUID queryRequestId, UUID commentId,
                                    CollaboratorContext context) {
        var root = findRoot(queryRequestId, commentId, context);
        if (root.getStatus() == CommentStatus.RESOLVED) {
            return toView(root, loadUsers(List.of(root)));
        }
        root.setStatus(CommentStatus.RESOLVED);
        root.setResolvedBy(context.userId());
        root.setResolvedAt(Instant.now(clock));
        var saved = commentRepository.save(root);
        publish(queryRequestId, saved.getId(), CommentChangeType.RESOLVED, context);
        return toView(saved, loadUsers(List.of(saved)));
    }

    @Override
    @Transactional
    public QueryCommentView reopen(UUID queryRequestId, UUID commentId,
                                   CollaboratorContext context) {
        var root = findRoot(queryRequestId, commentId, context);
        if (root.getStatus() == CommentStatus.OPEN) {
            return toView(root, loadUsers(List.of(root)));
        }
        root.setStatus(CommentStatus.OPEN);
        root.setResolvedBy(null);
        root.setResolvedAt(null);
        var saved = commentRepository.save(root);
        publish(queryRequestId, saved.getId(), CommentChangeType.REOPENED, context);
        return toView(saved, loadUsers(List.of(saved)));
    }

    private QueryCommentEntity findRoot(UUID queryRequestId, UUID commentId,
                                        CollaboratorContext context) {
        authorize(queryRequestId, context);
        var comment = commentRepository.findByIdAndQueryRequestId(commentId, queryRequestId)
                .orElseThrow(() -> new QueryCommentNotFoundException(commentId));
        if (comment.getParentCommentId() != null) {
            // Only thread roots have a resolvable status.
            throw new QueryCommentNotFoundException(commentId);
        }
        return comment;
    }

    private QueryRequestSnapshot authorize(UUID queryRequestId, CollaboratorContext context) {
        var snapshot = queryRequestLookupService.findById(queryRequestId)
                .filter(s -> s.organizationId().equals(context.organizationId()))
                .orElseThrow(() -> new QueryRequestNotFoundException(queryRequestId));
        if (!collaborationAccessService.canCollaborate(queryRequestId, context.userId(),
                context.organizationId(), context.roleName(), context.permissions())) {
            throw new CollaborationNotPermittedException(queryRequestId);
        }
        return snapshot;
    }

    private void publish(UUID queryRequestId, UUID commentId, CommentChangeType changeType,
                         CollaboratorContext context) {
        var recipients = collaborationAccessService.collaboratorIds(queryRequestId,
                context.organizationId());
        eventPublisher.publishEvent(new QueryCommentChangedEvent(queryRequestId, commentId,
                changeType, context.userId(), recipients));
    }

    private Map<UUID, UserView> loadUsers(List<QueryCommentEntity> comments) {
        var users = new HashMap<UUID, UserView>();
        for (var comment : comments) {
            cacheUser(users, comment.getAuthorId());
            cacheUser(users, comment.getResolvedBy());
        }
        return users;
    }

    private void cacheUser(Map<UUID, UserView> cache, UUID userId) {
        if (userId == null || cache.containsKey(userId)) {
            return;
        }
        userQueryService.findById(userId).ifPresent(u -> cache.put(userId, u));
    }

    private QueryCommentView toView(QueryCommentEntity entity, Map<UUID, UserView> users) {
        return new QueryCommentView(
                entity.getId(),
                entity.getQueryRequestId(),
                entity.getParentCommentId(),
                ref(entity.getAuthorId(), users),
                entity.getAnchorStartLine(),
                entity.getAnchorEndLine(),
                entity.getAnchorSnapshot(),
                entity.getBody(),
                entity.getStatus(),
                entity.getResolvedBy() == null ? null : ref(entity.getResolvedBy(), users),
                entity.getResolvedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private CollaboratorRef ref(UUID userId, Map<UUID, UserView> users) {
        var user = users.get(userId);
        return user == null
                ? new CollaboratorRef(userId, null, null)
                : new CollaboratorRef(user.id(), user.displayName(), user.email());
    }
}
