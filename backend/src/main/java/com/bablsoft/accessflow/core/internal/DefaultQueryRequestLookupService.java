package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.PendingReviewView;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
class DefaultQueryRequestLookupService implements QueryRequestLookupService {

    private final QueryRequestRepository queryRequestRepository;
    private final AiAnalysisRepository aiAnalysisRepository;
    private final ReviewDecisionRepository reviewDecisionRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<QueryRequestSnapshot> findById(UUID queryRequestId) {
        return queryRequestRepository.findById(queryRequestId)
                .map(DefaultQueryRequestLookupService::toSnapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PendingReviewView> findPendingReview(UUID queryRequestId) {
        return queryRequestRepository.findById(queryRequestId)
                .map(this::toPendingReviewView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findTimedOutPendingReviewIds(Instant now) {
        return queryRequestRepository.findTimedOutPendingReviewIds(now);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PendingReviewView> findPendingForReviewer(UUID organizationId,
                                                          UUID reviewerUserId, UserRoleType role,
                                                          Pageable pageable) {
        return queryRequestRepository
                .findPendingForReviewer(organizationId, reviewerUserId, role,
                        QueryStatus.PENDING_REVIEW, pageable)
                .map(this::toPendingReviewView);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QueryListItemView> findForOrganization(QueryListFilter filter, Pageable pageable) {
        var spec = QueryRequestSpecifications.forFilter(filter);
        return queryRequestRepository.findAll(spec, pageable).map(this::toListItemView);
    }

    private static final int STREAM_PAGE_SIZE = 500;

    @Override
    @Transactional(readOnly = true)
    public long countForOrganization(QueryListFilter filter) {
        return queryRequestRepository.count(QueryRequestSpecifications.forFilter(filter));
    }

    @Override
    @Transactional(readOnly = true)
    public void streamForOrganization(QueryListFilter filter, int maxRows,
                                      Consumer<QueryListItemView> consumer) {
        if (maxRows <= 0) {
            return;
        }
        var spec = QueryRequestSpecifications.forFilter(filter);
        int emitted = 0;
        int pageIndex = 0;
        while (emitted < maxRows) {
            int remaining = maxRows - emitted;
            int pageSize = Math.min(STREAM_PAGE_SIZE, remaining);
            var page = queryRequestRepository.findAll(spec,
                    PageRequest.of(pageIndex, pageSize));
            for (var entity : page.getContent()) {
                consumer.accept(toListItemView(entity));
                emitted++;
                if (emitted >= maxRows) {
                    return;
                }
            }
            if (!page.hasNext()) {
                return;
            }
            pageIndex++;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QueryDetailView> findDetailById(UUID queryRequestId, UUID organizationId) {
        return queryRequestRepository.findById(queryRequestId)
                .filter(q -> q.getDatasource().getOrganization().getId().equals(organizationId))
                .map(this::toDetailView);
    }

    private PendingReviewView toPendingReviewView(QueryRequestEntity entity) {
        var aiAnalysis = entity.getAiAnalysisId() != null
                ? aiAnalysisRepository.findById(entity.getAiAnalysisId()).orElse(null)
                : null;
        return new PendingReviewView(
                entity.getId(),
                entity.getDatasource().getId(),
                entity.getDatasource().getName(),
                entity.getDatasource().getOrganization().getId(),
                entity.getSubmittedBy().getId(),
                entity.getSubmittedBy().getEmail(),
                entity.getSqlText(),
                entity.getQueryType(),
                entity.getStatus(),
                entity.getJustification(),
                aiAnalysis != null ? aiAnalysis.getId() : null,
                aiAnalysis != null ? aiAnalysis.getRiskLevel() : null,
                aiAnalysis != null ? aiAnalysis.getRiskScore() : null,
                aiAnalysis != null ? aiAnalysis.getSummary() : null,
                entity.getCreatedAt());
    }

    private QueryListItemView toListItemView(QueryRequestEntity entity) {
        var aiAnalysis = entity.getAiAnalysisId() != null
                ? aiAnalysisRepository.findById(entity.getAiAnalysisId()).orElse(null)
                : null;
        return new QueryListItemView(
                entity.getId(),
                entity.getDatasource().getId(),
                entity.getDatasource().getName(),
                entity.getSubmittedBy().getId(),
                entity.getSubmittedBy().getEmail(),
                entity.getSubmittedBy().getDisplayName(),
                entity.getQueryType(),
                entity.getStatus(),
                aiAnalysis != null ? aiAnalysis.getRiskLevel() : null,
                aiAnalysis != null ? aiAnalysis.getRiskScore() : null,
                entity.getCreatedAt());
    }

    private QueryDetailView toDetailView(QueryRequestEntity entity) {
        var aiAnalysis = entity.getAiAnalysisId() != null
                ? aiAnalysisRepository.findById(entity.getAiAnalysisId()).orElse(null)
                : null;
        var plan = entity.getDatasource().getReviewPlan();
        var decisions = reviewDecisionRepository
                .findAllByQueryRequest_IdOrderByDecidedAtAsc(entity.getId())
                .stream()
                .map(DefaultQueryRequestLookupService::toReviewDecisionView)
                .toList();
        return new QueryDetailView(
                entity.getId(),
                entity.getDatasource().getId(),
                entity.getDatasource().getName(),
                entity.getDatasource().getOrganization().getId(),
                entity.getSubmittedBy().getId(),
                entity.getSubmittedBy().getEmail(),
                entity.getSubmittedBy().getDisplayName(),
                entity.getSqlText(),
                entity.getQueryType(),
                entity.getStatus(),
                entity.getJustification(),
                toAnalysisDetail(aiAnalysis),
                entity.getRowsAffected(),
                entity.getExecutionDurationMs(),
                entity.getErrorMessage(),
                plan != null ? plan.getName() : null,
                plan != null ? plan.getApprovalTimeoutHours() : null,
                decisions,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static QueryDetailView.ReviewDecisionView toReviewDecisionView(ReviewDecisionEntity entity) {
        var reviewer = entity.getReviewer();
        return new QueryDetailView.ReviewDecisionView(
                entity.getId(),
                new QueryDetailView.ReviewerRef(
                        reviewer.getId(),
                        reviewer.getEmail(),
                        reviewer.getDisplayName()),
                entity.getDecision(),
                entity.getComment(),
                entity.getStage(),
                entity.getDecidedAt());
    }

    private static QueryDetailView.AiAnalysisDetail toAnalysisDetail(AiAnalysisEntity entity) {
        if (entity == null) {
            return null;
        }
        return new QueryDetailView.AiAnalysisDetail(
                entity.getId(),
                entity.getRiskLevel(),
                entity.getRiskScore(),
                entity.getSummary(),
                entity.getIssues(),
                entity.isMissingIndexesDetected(),
                entity.getAffectsRowEstimate(),
                entity.getAiProvider(),
                entity.getAiModel(),
                entity.getPromptTokens(),
                entity.getCompletionTokens());
    }

    private static QueryRequestSnapshot toSnapshot(QueryRequestEntity entity) {
        return new QueryRequestSnapshot(
                entity.getId(),
                entity.getDatasource().getId(),
                entity.getDatasource().getOrganization().getId(),
                entity.getSubmittedBy().getId(),
                entity.getSqlText(),
                entity.getQueryType(),
                entity.getStatus());
    }
}
