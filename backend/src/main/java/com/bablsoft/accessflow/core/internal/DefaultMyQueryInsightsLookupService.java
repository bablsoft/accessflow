package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MyOptimizationSourceView;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.core.api.MyQueryRiskBucket;
import com.bablsoft.accessflow.core.api.MyQueryStatusBucket;
import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.MyQueryTrendsRaw;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.internal.persistence.repo.MyQueryInsightsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultMyQueryInsightsLookupService implements MyQueryInsightsLookupService {

    private final MyQueryInsightsRepository repository;

    @Override
    @Transactional(readOnly = true)
    public MyQueryTrendsRaw trends(UUID organizationId, UUID userId, Instant from, Instant to) {
        requireScope(organizationId, userId);
        List<MyQueryStatusBucket> statusByDay = repository
                .findStatusByDay(organizationId, userId, from, to).stream()
                .map(r -> new MyQueryStatusBucket(r.getBucketDate(),
                        QueryStatus.valueOf(r.getStatus()), r.getCnt()))
                .toList();
        List<MyQueryRiskBucket> riskByDay = repository
                .findRiskByDay(organizationId, userId, from, to).stream()
                .map(r -> new MyQueryRiskBucket(r.getBucketDate(),
                        RiskLevel.valueOf(r.getRiskLevel()), r.getCnt()))
                .toList();
        return new MyQueryTrendsRaw(statusByDay, riskByDay);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyQueryStatusCount> statusCounts(UUID organizationId, UUID userId) {
        requireScope(organizationId, userId);
        return repository.findStatusCounts(organizationId, userId).stream()
                .map(r -> new MyQueryStatusCount(QueryStatus.valueOf(r.getStatus()), r.getCnt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyOptimizationSourceView> recentOptimizationSources(UUID organizationId, UUID userId,
                                                                    int limit) {
        requireScope(organizationId, userId);
        if (limit <= 0) {
            return List.of();
        }
        return repository.findRecentOptimizationSources(organizationId, userId, limit).stream()
                .map(r -> new MyOptimizationSourceView(
                        r.getAiAnalysisId(),
                        r.getQueryRequestId(),
                        r.getDatasourceId(),
                        r.getDatasourceName(),
                        DbType.valueOf(r.getDbType()),
                        RiskLevel.valueOf(r.getRiskLevel()),
                        r.getOptimizations(),
                        r.getCreatedAt()))
                .toList();
    }

    private static void requireScope(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            throw new IllegalArgumentException("organizationId and userId are required");
        }
    }
}
