package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiAnalysisLookupService implements AiAnalysisLookupService {

    private final AiAnalysisRepository aiAnalysisRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<AiAnalysisSummaryView> findByQueryRequestId(UUID queryRequestId) {
        return aiAnalysisRepository.findByQueryRequest_Id(queryRequestId).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiAnalysisSummaryView> findById(UUID analysisId) {
        if (analysisId == null) {
            return Optional.empty();
        }
        return aiAnalysisRepository.findById(analysisId).map(this::toView);
    }

    private AiAnalysisSummaryView toView(
            com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity a) {
        return new AiAnalysisSummaryView(
                a.getId(),
                a.getQueryRequest() != null ? a.getQueryRequest().getId() : null,
                a.getRiskLevel(),
                a.getRiskScore(),
                a.getSummary(),
                a.isFailed(),
                a.getErrorMessage());
    }
}
