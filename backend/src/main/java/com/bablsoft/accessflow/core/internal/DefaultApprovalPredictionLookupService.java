package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ApprovalPredictionLookupService;
import com.bablsoft.accessflow.core.api.ApprovalPredictionSnapshot;
import com.bablsoft.accessflow.core.internal.persistence.entity.ApprovalPredictionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ApprovalPredictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApprovalPredictionLookupService implements ApprovalPredictionLookupService {

    private final ApprovalPredictionRepository approvalPredictionRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalPredictionSnapshot> findByQueryRequestId(UUID queryRequestId) {
        return approvalPredictionRepository.findByQueryRequestId(queryRequestId)
                .map(this::toSnapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalPredictionSnapshot> findByQueryRequestIds(
            Collection<UUID> queryRequestIds) {
        if (queryRequestIds == null || queryRequestIds.isEmpty()) {
            return List.of();
        }
        return approvalPredictionRepository.findByQueryRequestIdIn(queryRequestIds).stream()
                .map(this::toSnapshot)
                .toList();
    }

    private ApprovalPredictionSnapshot toSnapshot(ApprovalPredictionEntity entity) {
        return new ApprovalPredictionSnapshot(
                entity.getId(),
                entity.getQueryRequest().getId(),
                entity.getProbability(),
                entity.getModelId(),
                entity.getFeatureSchemaVersion(),
                entity.getFeatures(),
                entity.isSkipped(),
                entity.getSkippedReason(),
                entity.isFailed(),
                entity.getErrorMessage(),
                entity.getCreatedAt());
    }
}
