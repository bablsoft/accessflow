package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.QueryRequestStateService;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.RecordApprovalCommand;
import com.partqam.accessflow.core.api.RecordDecisionResult;
import com.partqam.accessflow.core.api.ReviewDecisionSnapshot;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryRequestStateService implements QueryRequestStateService {

    private final QueryRequestRepository queryRequestRepository;
    private final ReviewDecisionRepository reviewDecisionRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void transitionTo(UUID queryRequestId, QueryStatus expected, QueryStatus next) {
        var entity = lockOrThrow(queryRequestId);
        if (entity.getStatus() != expected) {
            throw new IllegalQueryStatusTransitionException(queryRequestId, entity.getStatus(),
                    expected);
        }
        entity.setStatus(next);
        queryRequestRepository.save(entity);
    }

    @Override
    @Transactional
    public RecordDecisionResult recordApprovalAndAdvance(RecordApprovalCommand command) {
        var entity = lockOrThrow(command.queryRequestId());
        if (entity.getStatus() != QueryStatus.PENDING_REVIEW) {
            throw new IllegalQueryStatusTransitionException(command.queryRequestId(),
                    entity.getStatus(), QueryStatus.PENDING_REVIEW);
        }
        var existing = findExistingDecision(command.queryRequestId(), command.reviewerId(),
                command.stage());
        if (existing != null) {
            return new RecordDecisionResult(existing.getId(), entity.getStatus(), true);
        }
        var inserted = persistDecision(entity, command.reviewerId(), DecisionType.APPROVED,
                command.comment(), command.stage());
        var stageApproved = countApprovalsAtStage(command.queryRequestId(), command.stage());
        if (stageApproved >= command.minApprovalsRequired() && command.isLastStage()) {
            entity.setStatus(QueryStatus.APPROVED);
            queryRequestRepository.save(entity);
            return new RecordDecisionResult(inserted.getId(), QueryStatus.APPROVED, false);
        }
        return new RecordDecisionResult(inserted.getId(), QueryStatus.PENDING_REVIEW, false);
    }

    @Override
    @Transactional
    public RecordDecisionResult recordRejection(UUID queryRequestId, UUID reviewerId, int stage,
                                                String comment) {
        var entity = lockOrThrow(queryRequestId);
        if (entity.getStatus() != QueryStatus.PENDING_REVIEW) {
            throw new IllegalQueryStatusTransitionException(queryRequestId, entity.getStatus(),
                    QueryStatus.PENDING_REVIEW);
        }
        var existing = findExistingDecision(queryRequestId, reviewerId, stage);
        if (existing != null) {
            return new RecordDecisionResult(existing.getId(), entity.getStatus(), true);
        }
        var inserted = persistDecision(entity, reviewerId, DecisionType.REJECTED, comment, stage);
        entity.setStatus(QueryStatus.REJECTED);
        queryRequestRepository.save(entity);
        return new RecordDecisionResult(inserted.getId(), QueryStatus.REJECTED, false);
    }

    @Override
    @Transactional
    public RecordDecisionResult recordChangesRequested(UUID queryRequestId, UUID reviewerId,
                                                       int stage, String comment) {
        var entity = lockOrThrow(queryRequestId);
        if (entity.getStatus() != QueryStatus.PENDING_REVIEW) {
            throw new IllegalQueryStatusTransitionException(queryRequestId, entity.getStatus(),
                    QueryStatus.PENDING_REVIEW);
        }
        var existing = findExistingDecision(queryRequestId, reviewerId, stage);
        if (existing != null) {
            return new RecordDecisionResult(existing.getId(), entity.getStatus(), true);
        }
        var inserted = persistDecision(entity, reviewerId, DecisionType.REQUESTED_CHANGES, comment,
                stage);
        return new RecordDecisionResult(inserted.getId(), QueryStatus.PENDING_REVIEW, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDecisionSnapshot> listDecisions(UUID queryRequestId) {
        return reviewDecisionRepository.findAllByQueryRequest_IdOrderByDecidedAtAsc(queryRequestId)
                .stream()
                .map(DefaultQueryRequestStateService::toSnapshot)
                .toList();
    }

    private QueryRequestEntity lockOrThrow(UUID id) {
        return queryRequestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new QueryRequestNotFoundException(id));
    }

    private ReviewDecisionEntity findExistingDecision(UUID queryRequestId, UUID reviewerId,
                                                      int stage) {
        return reviewDecisionRepository
                .findAllByQueryRequest_IdAndStage(queryRequestId, stage).stream()
                .filter(d -> d.getReviewer().getId().equals(reviewerId))
                .findFirst()
                .orElse(null);
    }

    private ReviewDecisionEntity persistDecision(QueryRequestEntity query, UUID reviewerId,
                                                 DecisionType decision, String comment,
                                                 int stage) {
        var reviewer = userRepository.findById(reviewerId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + reviewerId));
        var entity = new ReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(query);
        entity.setReviewer(reviewer);
        entity.setDecision(decision);
        entity.setComment(comment);
        entity.setStage(stage);
        return reviewDecisionRepository.save(entity);
    }

    private int countApprovalsAtStage(UUID queryRequestId, int stage) {
        return (int) reviewDecisionRepository
                .findAllByQueryRequest_IdAndStage(queryRequestId, stage).stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVED)
                .count();
    }

    private static ReviewDecisionSnapshot toSnapshot(ReviewDecisionEntity entity) {
        return new ReviewDecisionSnapshot(
                entity.getId(),
                entity.getQueryRequest().getId(),
                entity.getReviewer().getId(),
                entity.getDecision(),
                entity.getComment(),
                entity.getStage(),
                entity.getDecidedAt());
    }
}
