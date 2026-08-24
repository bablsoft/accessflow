package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.core.api.PersistAiModelResultCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisModelResultEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisModelResultRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiAnalysisPersistenceService implements AiAnalysisPersistenceService {

    private final AiAnalysisRepository aiAnalysisRepository;
    private final AiAnalysisModelResultRepository modelResultRepository;
    private final QueryRequestRepository queryRequestRepository;

    @Override
    @Transactional
    public UUID persist(UUID queryRequestId, PersistAiAnalysisCommand command) {
        var queryRequest = queryRequestRepository.findById(queryRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Query request not found: " + queryRequestId));
        var entity = newAnalysis(command);
        entity.setQueryRequest(queryRequest);
        var saved = aiAnalysisRepository.save(entity);
        queryRequest.setAiAnalysisId(saved.getId());
        persistModelResults(saved.getId(), command);
        return saved.getId();
    }

    private void persistModelResults(UUID analysisId, PersistAiAnalysisCommand command) {
        for (PersistAiModelResultCommand mr : command.modelResults()) {
            var row = new AiAnalysisModelResultEntity();
            row.setId(UUID.randomUUID());
            row.setAiAnalysisId(analysisId);
            row.setAiProvider(mr.provider());
            row.setAiModel(mr.model());
            row.setRiskScore(mr.riskScore());
            row.setRiskLevel(mr.riskLevel());
            row.setWeight(mr.weight());
            row.setPromptTokens(mr.promptTokens());
            row.setCompletionTokens(mr.completionTokens());
            row.setLatencyMs(mr.latencyMs());
            row.setFailed(mr.failed());
            row.setErrorMessage(mr.errorMessage());
            row.setCreatedAt(Instant.now());
            modelResultRepository.save(row);
        }
    }

    @Override
    @Transactional
    public UUID persistForApiRequest(UUID apiRequestId, PersistAiAnalysisCommand command) {
        var entity = newAnalysis(command);
        entity.setApiRequestId(apiRequestId);
        var saved = aiAnalysisRepository.save(entity);
        persistModelResults(saved.getId(), command);
        return saved.getId();
    }

    @Override
    @Transactional
    public UUID persistForGroupItem(UUID requestGroupItemId, PersistAiAnalysisCommand command) {
        var entity = newAnalysis(command);
        entity.setRequestGroupItemId(requestGroupItemId);
        var saved = aiAnalysisRepository.save(entity);
        persistModelResults(saved.getId(), command);
        return saved.getId();
    }

    @Override
    @Transactional
    public UUID persistForDeploymentRequest(UUID deploymentRequestId, PersistAiAnalysisCommand command) {
        var entity = newAnalysis(command);
        entity.setDeploymentRequestId(deploymentRequestId);
        var saved = aiAnalysisRepository.save(entity);
        persistModelResults(saved.getId(), command);
        return saved.getId();
    }

    /**
     * A new analysis row with everything but the target key set. Exactly one target column must be
     * populated by the caller — {@code chk_ai_analyses_target} enforces it.
     */
    private static AiAnalysisEntity newAnalysis(PersistAiAnalysisCommand command) {
        var entity = new AiAnalysisEntity();
        entity.setId(UUID.randomUUID());
        entity.setAiProvider(command.aiProvider());
        entity.setAiModel(command.aiModel());
        entity.setRiskScore(command.riskScore());
        entity.setRiskLevel(command.riskLevel());
        entity.setSummary(command.summary());
        entity.setIssues(command.issuesJson());
        entity.setOptimizations(command.optimizationsJson());
        entity.setMissingIndexesDetected(command.missingIndexesDetected());
        entity.setAffectsRowEstimate(command.affectsRowEstimate());
        entity.setPromptTokens(command.promptTokens());
        entity.setCompletionTokens(command.completionTokens());
        entity.setFailed(command.failed());
        entity.setErrorMessage(command.errorMessage());
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    @Override
    @Transactional
    public void deleteForQuery(UUID queryRequestId) {
        var queryRequest = queryRequestRepository.findById(queryRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Query request not found: " + queryRequestId));
        var existingId = queryRequest.getAiAnalysisId();
        queryRequest.setAiAnalysisId(null);
        if (existingId != null) {
            aiAnalysisRepository.deleteById(existingId);
        }
    }
}
