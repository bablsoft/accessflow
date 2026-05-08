package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.PendingReviewView;
import com.partqam.accessflow.core.api.QueryDetailView;
import com.partqam.accessflow.core.api.QueryListFilter;
import com.partqam.accessflow.core.api.QueryListItemView;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryRequestLookupService implements QueryRequestLookupService {

    private final QueryRequestRepository queryRequestRepository;
    private final AiAnalysisRepository aiAnalysisRepository;

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
                .findPendingForReviewer(organizationId, reviewerUserId, role, pageable)
                .map(this::toPendingReviewView);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QueryListItemView> findForOrganization(QueryListFilter filter, Pageable pageable) {
        var spec = QueryRequestSpecifications.forFilter(filter);
        return queryRequestRepository.findAll(spec, pageable).map(this::toListItemView);
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
                entity.getCreatedAt(),
                entity.getUpdatedAt());
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
