package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.PendingReviewView;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryOccurrenceView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ApprovalPredictionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryEstimateEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ApprovalPredictionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryEstimateRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
import lombok.RequiredArgsConstructor;
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
    private final QueryEstimateRepository queryEstimateRepository;
    private final ApprovalPredictionRepository approvalPredictionRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<QueryRequestSnapshot> findById(UUID queryRequestId) {
        return queryRequestRepository.findById(queryRequestId)
                .map(DefaultQueryRequestLookupService::toSnapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findCreatedAt(UUID queryRequestId) {
        return queryRequestRepository.findById(queryRequestId)
                .map(QueryRequestEntity::getCreatedAt);
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
    public List<UUID> findScheduledDueIds(Instant now) {
        return queryRequestRepository.findScheduledDueIds(now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findRecurringDueIds(Instant now) {
        return queryRequestRepository.findRecurringDueIds(now);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QueryOccurrenceView> findOccurrences(UUID parentId, UUID organizationId,
                                                             PageRequest pageRequest) {
        var pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.page(), pageRequest.size(),
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        var page = queryRequestRepository.findOccurrences(parentId, organizationId, pageable);
        return PageAdapter.toPageResponse(page.map(DefaultQueryRequestLookupService::toOccurrenceView));
    }

    private static QueryOccurrenceView toOccurrenceView(QueryRequestEntity entity) {
        return new QueryOccurrenceView(
                entity.getId(),
                entity.getStatus(),
                entity.getRowsAffected(),
                entity.getExecutionDurationMs(),
                entity.getExecutionCompletedAt(),
                entity.getErrorMessage(),
                entity.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingReviewView> findPendingForReviewer(UUID organizationId,
                                                                  UUID reviewerUserId,
                                                                  String roleName,
                                                                  PageRequest pageRequest) {
        var page = queryRequestRepository
                .findPendingForReviewer(organizationId, reviewerUserId, roleName,
                        QueryStatus.PENDING_REVIEW, PageAdapter.toSpringPageable(pageRequest));
        return PageAdapter.toPageResponse(page.map(this::toPendingReviewView));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QueryListItemView> findForOrganization(QueryListFilter filter,
                                                               PageRequest pageRequest) {
        var spec = QueryRequestSpecifications.forFilter(filter);
        var page = queryRequestRepository.findAll(spec, PageAdapter.toSpringPageable(pageRequest));
        return PageAdapter.toPageResponse(page.map(this::toListItemView));
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
                    org.springframework.data.domain.PageRequest.of(pageIndex, pageSize));
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

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findPreviousRunId(UUID submitterId, UUID datasourceId,
                                            String canonicalSql, UUID excludeQueryId) {
        if (canonicalSql == null) {
            return Optional.empty();
        }
        var matches = queryRequestRepository.findPreviousExecutedRunIds(
                QueryStatus.EXECUTED, submitterId, datasourceId, canonicalSql, excludeQueryId,
                org.springframework.data.domain.PageRequest.of(0, 1));
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
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
                aiAnalysis != null && aiAnalysis.isFailed(),
                entity.getScheduledFor(),
                entity.getRecurrenceRule() != null,
                entity.getRecurringParentId(),
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
                entity.getDatasource().getDbType(),
                entity.getDatasource().getOrganization().getId(),
                entity.getSubmittedBy().getId(),
                entity.getSubmittedBy().getEmail(),
                entity.getSubmittedBy().getDisplayName(),
                entity.getSqlText(),
                entity.getQueryType(),
                entity.getStatus(),
                entity.getJustification(),
                toAnalysisDetail(aiAnalysis),
                toCostEstimateDetail(entity.getQueryEstimateId() != null
                        ? queryEstimateRepository.findById(entity.getQueryEstimateId()).orElse(null)
                        : null),
                // No reciprocal FK on query_requests (approval_predictions.query_request_id is the
                // only link), so this cannot short-circuit the way the cost estimate above does.
                toApprovalPredictionDetail(approvalPredictionRepository
                        .findByQueryRequestId(entity.getId())
                        .orElse(null)),
                entity.getRowsAffected(),
                entity.getExecutionDurationMs(),
                entity.getErrorMessage(),
                entity.getPreviousRunId(),
                entity.getApprovedByGrantId(),
                plan != null ? plan.getName() : null,
                plan != null ? plan.getApprovalTimeoutHours() : null,
                decisions,
                entity.getScheduledFor(),
                entity.getRecurrenceRule(),
                entity.getRecurrenceUntil(),
                entity.getRecurrenceNextRunAt(),
                entity.getRecurrenceHaltedReason(),
                entity.getRecurringParentId(),
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
                entity.getOptimizations(),
                entity.isMissingIndexesDetected(),
                entity.getAffectsRowEstimate(),
                entity.getAiProvider(),
                entity.getAiModel(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.isFailed(),
                entity.getErrorMessage());
    }

    private static QueryDetailView.CostEstimateDetail toCostEstimateDetail(
            QueryEstimateEntity entity) {
        if (entity == null) {
            return null;
        }
        return new QueryDetailView.CostEstimateDetail(
                entity.getId(),
                entity.getEngineId(),
                entity.getQueryType(),
                entity.isSupported(),
                entity.getEstimatedRows(),
                entity.getAffectedRowCount(),
                entity.getScanType(),
                entity.getEstimatedCost(),
                entity.getPlan(),
                entity.getRawPlan(),
                entity.getUnsupportedReason(),
                entity.isFailed(),
                entity.getErrorMessage(),
                entity.getDurationMs());
    }

    private static QueryDetailView.ApprovalPredictionDetail toApprovalPredictionDetail(
            ApprovalPredictionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new QueryDetailView.ApprovalPredictionDetail(
                entity.getId(),
                entity.getProbability(),
                entity.isSkipped(),
                entity.getSkippedReason(),
                entity.isFailed(),
                entity.getCreatedAt());
    }

    private static QueryRequestSnapshot toSnapshot(QueryRequestEntity entity) {
        return new QueryRequestSnapshot(
                entity.getId(),
                entity.getDatasource().getId(),
                entity.getDatasource().getOrganization().getId(),
                entity.getSubmittedBy().getId(),
                entity.getSqlText(),
                entity.getQueryType(),
                entity.isTransactional(),
                entity.getStatus(),
                entity.getScheduledFor(),
                entity.getSubmittedIp(),
                entity.getSubmittedUserAgent(),
                entity.isCiCdOrigin(),
                entity.getRecurrenceRule(),
                entity.getRecurrenceUntil(),
                entity.getRecurrenceNextRunAt(),
                entity.getRecurringParentId());
    }

    private static final List<QueryStatus> APPROVED_STATUSES =
            List.of(QueryStatus.APPROVED, QueryStatus.EXECUTED);

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findLastApprovalInstant(UUID organizationId, UUID userId,
                                                     UUID datasourceId, UUID excludingQueryId) {
        return queryRequestRepository.findLastApprovalInstant(organizationId, userId, datasourceId,
                APPROVED_STATUSES, excludingQueryId);
    }
}
