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
        return aiAnalysisRepository.findByQueryRequest_Id(queryRequestId)
                .map(a -> new AiAnalysisSummaryView(
                        a.getId(),
                        a.getQueryRequest().getId(),
                        a.getRiskLevel(),
                        a.getRiskScore(),
                        a.getSummary()));
    }
}
