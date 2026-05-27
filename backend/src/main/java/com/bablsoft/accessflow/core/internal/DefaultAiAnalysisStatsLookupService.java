package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisIssueCategoryView;
import com.bablsoft.accessflow.core.api.AiAnalysisRiskScoreBucketView;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsRaw;
import com.bablsoft.accessflow.core.api.AiAnalysisSubmitterView;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiAnalysisStatsLookupService implements AiAnalysisStatsLookupService {

    private final AiAnalysisStatsRepository repository;

    @Override
    @Transactional(readOnly = true)
    public AiAnalysisStatsRaw query(UUID organizationId, Instant from, Instant to, UUID datasourceId) {
        List<AiAnalysisRiskScoreBucketView> riskScoreOverTime = repository
                .findRiskScoreByDay(organizationId, from, to, datasourceId).stream()
                .map(r -> new AiAnalysisRiskScoreBucketView(
                        r.getBucketDate(),
                        r.getSuccessAvgRiskScore(),
                        r.getTotalCount(),
                        r.getSuccessCount()))
                .toList();
        List<AiAnalysisIssueCategoryView> topIssueCategories = repository
                .findTopIssueCategories(organizationId, from, to, datasourceId).stream()
                .map(r -> new AiAnalysisIssueCategoryView(r.getCategory(), r.getCnt()))
                .toList();
        List<AiAnalysisSubmitterView> topSubmitters = repository
                .findTopSubmitters(organizationId, from, to, datasourceId).stream()
                .map(r -> new AiAnalysisSubmitterView(
                        r.getUserId(), r.getEmail(), r.getDisplayName(), r.getCnt()))
                .toList();
        return new AiAnalysisStatsRaw(riskScoreOverTime, topIssueCategories, topSubmitters);
    }
}
