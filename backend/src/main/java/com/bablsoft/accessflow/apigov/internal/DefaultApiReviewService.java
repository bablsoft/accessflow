package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiReviewService;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.SelfApprovalNotAllowedException;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiReviewDecisionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiReviewDecisionRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiReviewService implements ApiReviewService {

    private static final int STAGE = 1;

    private final ApiRequestRepository requestRepository;
    private final ApiReviewDecisionRepository decisionRepository;
    private final ApiConnectorRepository connectorRepository;
    private final ApiRequestStateService stateService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingApiReview> listPending(ReviewerContext context, PageRequest pageRequest) {
        var page = requestRepository.findByOrganizationIdAndStatus(context.organizationId(),
                QueryStatus.PENDING_REVIEW,
                org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size()));
        var content = page.getContent().stream()
                .filter(r -> !r.getSubmittedBy().equals(context.userId()))
                .map(this::toPending)
                .toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize() <= 0 ? 1 : page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID apiRequestId, ReviewerContext context, String comment) {
        var request = require(apiRequestId, context.organizationId());
        guardReviewable(request, context);
        var existing = decisionRepository.findByApiRequestIdAndReviewerIdAndStage(
                apiRequestId, context.userId(), STAGE);
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), existing.get().getDecision(),
                    request.getStatus(), true);
        }
        var decision = record(apiRequestId, context.userId(), DecisionType.APPROVED, comment);
        long approvals = decisionRepository.countByApiRequestIdAndStageAndDecision(
                apiRequestId, STAGE, DecisionType.APPROVED);
        QueryStatus resulting = request.getStatus();
        if (approvals >= request.getRequiredApprovals()) {
            stateService.apply(request, QueryStatus.APPROVED);
            eventPublisher.publishEvent(new ApiRequestDecidedEvent(apiRequestId, QueryStatus.APPROVED, null));
            resulting = QueryStatus.APPROVED;
        }
        return new DecisionOutcome(decision.getId(), DecisionType.APPROVED, resulting, false);
    }

    @Override
    @Transactional
    public DecisionOutcome reject(UUID apiRequestId, ReviewerContext context, String comment) {
        var request = require(apiRequestId, context.organizationId());
        guardReviewable(request, context);
        var existing = decisionRepository.findByApiRequestIdAndReviewerIdAndStage(
                apiRequestId, context.userId(), STAGE);
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), existing.get().getDecision(),
                    request.getStatus(), true);
        }
        var decision = record(apiRequestId, context.userId(), DecisionType.REJECTED, comment);
        stateService.apply(request, QueryStatus.REJECTED);
        eventPublisher.publishEvent(new ApiRequestDecidedEvent(apiRequestId, QueryStatus.REJECTED, null));
        return new DecisionOutcome(decision.getId(), DecisionType.REJECTED, QueryStatus.REJECTED, false);
    }

    private void guardReviewable(ApiRequestEntity request, ReviewerContext context) {
        if (request.getSubmittedBy().equals(context.userId())) {
            throw new SelfApprovalNotAllowedException();
        }
        if (request.getStatus() != QueryStatus.PENDING_REVIEW) {
            throw new IllegalApiRequestStateException(request.getStatus(),
                    "API request is not awaiting review");
        }
    }

    private ApiReviewDecisionEntity record(UUID apiRequestId, UUID reviewerId, DecisionType decision,
                                           String comment) {
        var entity = new ApiReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setApiRequestId(apiRequestId);
        entity.setReviewerId(reviewerId);
        entity.setDecision(decision);
        entity.setComment(comment);
        entity.setStage(STAGE);
        return decisionRepository.save(entity);
    }

    private ApiRequestEntity require(UUID id, UUID organizationId) {
        return requestRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new ApiRequestNotFoundException(id));
    }

    private PendingApiReview toPending(ApiRequestEntity e) {
        var connectorName = connectorRepository.findById(e.getConnectorId())
                .map(ApiConnectorEntity::getName).orElse(null);
        var summary = e.getAiAnalysisId() != null
                ? aiAnalysisLookupService.findById(e.getAiAnalysisId()).orElse(null) : null;
        return new PendingApiReview(e.getId(), e.getConnectorId(), connectorName, e.getSubmittedBy(),
                e.getVerb(), e.getRequestPath(), e.isWrite(), e.getJustification(), e.getAiAnalysisId(),
                summary != null ? summary.riskLevel() : null,
                summary != null ? summary.riskScore() : null,
                summary != null ? summary.summary() : null, STAGE, e.getCreatedAt());
    }
}
