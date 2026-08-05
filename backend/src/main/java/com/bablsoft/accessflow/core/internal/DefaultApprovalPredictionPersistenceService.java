package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ApprovalPredictionPersistenceService;
import com.bablsoft.accessflow.core.api.PersistApprovalPredictionCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.ApprovalPredictionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ApprovalPredictionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApprovalPredictionPersistenceService implements ApprovalPredictionPersistenceService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultApprovalPredictionPersistenceService.class);

    private final ApprovalPredictionRepository approvalPredictionRepository;
    private final QueryRequestRepository queryRequestRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public UUID persist(PersistApprovalPredictionCommand command) {
        var existing = approvalPredictionRepository.findByQueryRequestId(command.queryRequestId());
        if (existing.isPresent()) {
            var entity = existing.get();
            if (hadEstimateMissingSnapshot(entity.getFeatures())) {
                apply(entity, command);
                approvalPredictionRepository.save(entity);
            }
            return entity.getId();
        }
        var queryRequest = queryRequestRepository.findById(command.queryRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "Query request not found: " + command.queryRequestId()));
        var entity = new ApprovalPredictionEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(queryRequest);
        apply(entity, command);
        return approvalPredictionRepository.save(entity).getId();
    }

    private void apply(ApprovalPredictionEntity entity, PersistApprovalPredictionCommand command) {
        entity.setProbability(command.probability());
        entity.setModelId(command.modelId());
        entity.setFeatureSchemaVersion(command.featureSchemaVersion());
        entity.setFeatures(command.featuresJson());
        entity.setSkipped(command.skipped());
        entity.setSkippedReason(command.skippedReason());
        entity.setFailed(command.failed());
        entity.setErrorMessage(command.errorMessage());
    }

    private boolean hadEstimateMissingSnapshot(String featuresJson) {
        if (featuresJson == null) {
            return false;
        }
        try {
            return objectMapper.readTree(featuresJson).path("estimate_missing").asBoolean(false);
        } catch (RuntimeException e) {
            log.debug("Unparseable approval_predictions.features JSON, treating as insert-once", e);
            return false;
        }
    }
}
