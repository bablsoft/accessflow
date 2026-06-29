package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureDecision;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewService;
import com.bablsoft.accessflow.lifecycle.api.ErasureSelfApprovalException;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestApprovedEvent;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestRejectedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestDecisionEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestDecisionRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultErasureReviewService implements ErasureReviewService {

    private final DeletionRequestRepository repository;
    private final DeletionRequestDecisionRepository decisionRepository;
    private final ErasureRequestViewMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ErasureRequestView> listPending(UUID organizationId, UUID reviewerId,
                                                        PageRequest pageRequest) {
        var page = repository.findAllByOrganizationIdAndStatusAndRequestedByNot(
                organizationId, ErasureStatus.PENDING_REVIEW, reviewerId,
                LifecyclePageAdapter.toSpringPageable(pageRequest));
        return LifecyclePageAdapter.toPageResponse(page).map(mapper::toView);
    }

    @Override
    @Transactional
    public ErasureRequestView approve(UUID requestId, ReviewerContext reviewer, String comment) {
        var entity = decide(requestId, reviewer, ErasureDecision.APPROVED, comment);
        entity.setStatus(ErasureStatus.APPROVED);
        var saved = repository.save(entity);
        eventPublisher.publishEvent(new ErasureRequestApprovedEvent(
                saved.getId(), saved.getOrganizationId(), reviewer.reviewerId()));
        return mapper.toView(saved);
    }

    @Override
    @Transactional
    public ErasureRequestView reject(UUID requestId, ReviewerContext reviewer, String comment) {
        var entity = decide(requestId, reviewer, ErasureDecision.REJECTED, comment);
        entity.setStatus(ErasureStatus.REJECTED);
        var saved = repository.save(entity);
        eventPublisher.publishEvent(new ErasureRequestRejectedEvent(
                saved.getId(), saved.getOrganizationId(), reviewer.reviewerId()));
        return mapper.toView(saved);
    }

    private DeletionRequestEntity decide(UUID requestId, ReviewerContext reviewer,
                                         ErasureDecision decision, String comment) {
        var entity = repository.findByIdForUpdate(requestId)
                .filter(e -> e.getOrganizationId().equals(reviewer.organizationId()))
                .orElseThrow(() -> new DeletionRequestNotFoundException(requestId));
        if (entity.getRequestedBy().equals(reviewer.reviewerId())) {
            throw new ErasureSelfApprovalException();
        }
        if (entity.getStatus() != ErasureStatus.PENDING_REVIEW) {
            throw new DeletionRequestInvalidStateException(entity.getStatus());
        }
        if (!decisionRepository.existsByRequestIdAndReviewerId(requestId, reviewer.reviewerId())) {
            var row = new DeletionRequestDecisionEntity();
            row.setId(UUID.randomUUID());
            row.setRequestId(requestId);
            row.setReviewerId(reviewer.reviewerId());
            row.setStage(1);
            row.setDecision(decision);
            row.setComment(comment);
            decisionRepository.save(row);
        }
        return entity;
    }
}
