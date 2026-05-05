package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.PendingReviewView;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public Page<PendingReviewView> findPendingForReviewer(UUID organizationId,
                                                          UUID reviewerUserId, UserRoleType role,
                                                          Pageable pageable) {
        return queryRequestRepository
                .findPendingForReviewer(organizationId, reviewerUserId, role, pageable)
                .map(this::toPendingReviewView);
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
