package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureDecision;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestDecisionEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestDecisionRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultErasureRequestStateService implements ErasureRequestStateService {

    private final DeletionRequestRepository requestRepository;
    private final DeletionRequestDecisionRepository decisionRepository;

    @Override
    @Transactional
    public RecordErasureDecisionResult recordApprovalAndAdvance(RecordErasureApprovalCommand command) {
        var entity = lockOrThrow(command.requestId());
        requirePendingReview(entity);
        var existing = findExistingDecision(command.requestId(), command.reviewerId(),
                command.stage());
        if (existing != null) {
            return new RecordErasureDecisionResult(existing.getId(), entity.getStatus(), true);
        }
        var inserted = persistDecision(command.requestId(), command.reviewerId(),
                ErasureDecision.APPROVED, command.comment(), command.stage());
        var stageApproved = countApprovalsAtStage(command.requestId(), command.stage());
        if (stageApproved >= command.minApprovalsRequired() && command.isLastStage()) {
            entity.setStatus(ErasureStatus.APPROVED);
            requestRepository.save(entity);
            return new RecordErasureDecisionResult(inserted.getId(), ErasureStatus.APPROVED, false);
        }
        return new RecordErasureDecisionResult(inserted.getId(), ErasureStatus.PENDING_REVIEW, false);
    }

    @Override
    @Transactional
    public RecordErasureDecisionResult recordRejection(UUID requestId, UUID reviewerId, int stage,
                                                       String comment) {
        var entity = lockOrThrow(requestId);
        requirePendingReview(entity);
        var existing = findExistingDecision(requestId, reviewerId, stage);
        if (existing != null) {
            return new RecordErasureDecisionResult(existing.getId(), entity.getStatus(), true);
        }
        var inserted = persistDecision(requestId, reviewerId, ErasureDecision.REJECTED, comment, stage);
        entity.setStatus(ErasureStatus.REJECTED);
        requestRepository.save(entity);
        return new RecordErasureDecisionResult(inserted.getId(), ErasureStatus.REJECTED, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ErasureDecisionSnapshot> listDecisions(UUID requestId) {
        return decisionRepository.findAllByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .map(DefaultErasureRequestStateService::toSnapshot)
                .toList();
    }

    @Override
    @Transactional
    public boolean markTimedOut(UUID requestId) {
        var entity = requestRepository.findByIdForUpdate(requestId).orElse(null);
        if (entity == null || entity.getStatus() != ErasureStatus.PENDING_REVIEW) {
            return false;
        }
        entity.setStatus(ErasureStatus.REJECTED);
        entity.setFailureReason("review timeout");
        requestRepository.save(entity);
        return true;
    }

    private DeletionRequestEntity lockOrThrow(UUID requestId) {
        return requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new DeletionRequestNotFoundException(requestId));
    }

    private static void requirePendingReview(DeletionRequestEntity entity) {
        if (entity.getStatus() != ErasureStatus.PENDING_REVIEW) {
            throw new DeletionRequestInvalidStateException(entity.getStatus());
        }
    }

    private DeletionRequestDecisionEntity findExistingDecision(UUID requestId, UUID reviewerId,
                                                              int stage) {
        return decisionRepository.findAllByRequestIdAndStage(requestId, stage).stream()
                .filter(d -> d.getReviewerId().equals(reviewerId))
                .findFirst()
                .orElse(null);
    }

    private DeletionRequestDecisionEntity persistDecision(UUID requestId, UUID reviewerId,
                                                          ErasureDecision decision, String comment,
                                                          int stage) {
        var row = new DeletionRequestDecisionEntity();
        row.setId(UUID.randomUUID());
        row.setRequestId(requestId);
        row.setReviewerId(reviewerId);
        row.setStage(stage);
        row.setDecision(decision);
        row.setComment(comment);
        return decisionRepository.save(row);
    }

    private int countApprovalsAtStage(UUID requestId, int stage) {
        return (int) decisionRepository.findAllByRequestIdAndStage(requestId, stage).stream()
                .filter(d -> d.getDecision() == ErasureDecision.APPROVED)
                .count();
    }

    private static ErasureDecisionSnapshot toSnapshot(DeletionRequestDecisionEntity entity) {
        return new ErasureDecisionSnapshot(
                entity.getId(),
                entity.getRequestId(),
                entity.getReviewerId(),
                entity.getDecision(),
                entity.getComment(),
                entity.getStage(),
                entity.getCreatedAt());
    }
}
