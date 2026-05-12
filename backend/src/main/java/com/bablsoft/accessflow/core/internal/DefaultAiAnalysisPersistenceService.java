package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
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
    private final QueryRequestRepository queryRequestRepository;

    @Override
    @Transactional
    public UUID persist(UUID queryRequestId, PersistAiAnalysisCommand command) {
        var queryRequest = queryRequestRepository.findById(queryRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Query request not found: " + queryRequestId));
        var entity = new AiAnalysisEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(queryRequest);
        entity.setAiProvider(command.aiProvider());
        entity.setAiModel(command.aiModel());
        entity.setRiskScore(command.riskScore());
        entity.setRiskLevel(command.riskLevel());
        entity.setSummary(command.summary());
        entity.setIssues(command.issuesJson());
        entity.setMissingIndexesDetected(command.missingIndexesDetected());
        entity.setAffectsRowEstimate(command.affectsRowEstimate());
        entity.setPromptTokens(command.promptTokens());
        entity.setCompletionTokens(command.completionTokens());
        entity.setCreatedAt(Instant.now());
        var saved = aiAnalysisRepository.save(entity);
        queryRequest.setAiAnalysisId(saved.getId());
        return saved.getId();
    }
}
