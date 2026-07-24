package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.PersistQueryEstimateCommand;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryEstimatePersistenceService;
import com.bablsoft.accessflow.core.api.QueryEstimateSnapshot;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryEstimateEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryEstimateRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryEstimateService implements QueryEstimatePersistenceService,
        QueryEstimateLookupService {

    private final QueryEstimateRepository queryEstimateRepository;
    private final QueryRequestRepository queryRequestRepository;

    @Override
    @Transactional
    public UUID persist(UUID queryRequestId, PersistQueryEstimateCommand command) {
        var existing = queryEstimateRepository.findByQueryRequestId(queryRequestId);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        var queryRequest = queryRequestRepository.findById(queryRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Query request not found: " + queryRequestId));
        var entity = new QueryEstimateEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(queryRequest);
        entity.setEngineId(command.engineId());
        entity.setQueryType(command.queryType());
        entity.setSupported(command.supported());
        entity.setEstimatedRows(command.estimatedRows());
        entity.setAffectedRowCount(command.affectedRowCount());
        entity.setScanType(command.scanType());
        entity.setEstimatedCost(command.estimatedCost());
        entity.setPlan(command.planJson());
        entity.setRawPlan(command.rawPlan());
        entity.setUnsupportedReason(command.unsupportedReason());
        entity.setFailed(command.failed());
        entity.setErrorMessage(command.errorMessage());
        entity.setDurationMs(command.durationMs());
        entity.setCreatedAt(Instant.now());
        var saved = queryEstimateRepository.save(entity);
        queryRequest.setQueryEstimateId(saved.getId());
        return saved.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QueryEstimateSnapshot> findByQueryRequestId(UUID queryRequestId) {
        return queryEstimateRepository.findByQueryRequestId(queryRequestId).map(this::toSnapshot);
    }

    private QueryEstimateSnapshot toSnapshot(QueryEstimateEntity entity) {
        return new QueryEstimateSnapshot(
                entity.getId(),
                entity.getQueryRequest().getId(),
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
                entity.getDurationMs(),
                entity.getCreatedAt());
    }
}
