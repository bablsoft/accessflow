package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.MyApiRequestInsightsLookupService;
import com.bablsoft.accessflow.apigov.api.MyApiRequestRiskBucket;
import com.bablsoft.accessflow.apigov.api.MyApiRequestStatusBucket;
import com.bablsoft.accessflow.apigov.api.MyApiRequestStatusCount;
import com.bablsoft.accessflow.apigov.api.MyApiRequestTrendsRaw;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.MyApiRequestInsightsRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultMyApiRequestInsightsLookupService implements MyApiRequestInsightsLookupService {

    private final MyApiRequestInsightsRepository repository;

    @Override
    @Transactional(readOnly = true)
    public MyApiRequestTrendsRaw trends(UUID organizationId, UUID userId, Instant from, Instant to) {
        requireScope(organizationId, userId);
        List<MyApiRequestStatusBucket> statusByDay = repository
                .findStatusByDay(organizationId, userId, from, to).stream()
                .map(r -> new MyApiRequestStatusBucket(r.getBucketDate(),
                        QueryStatus.valueOf(r.getStatus()), r.getCnt()))
                .toList();
        List<MyApiRequestRiskBucket> riskByDay = repository
                .findRiskByDay(organizationId, userId, from, to).stream()
                .map(r -> new MyApiRequestRiskBucket(r.getBucketDate(),
                        RiskLevel.valueOf(r.getRiskLevel()), r.getCnt()))
                .toList();
        return new MyApiRequestTrendsRaw(statusByDay, riskByDay);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyApiRequestStatusCount> statusCounts(UUID organizationId, UUID userId) {
        requireScope(organizationId, userId);
        return repository.findStatusCounts(organizationId, userId).stream()
                .map(r -> new MyApiRequestStatusCount(QueryStatus.valueOf(r.getStatus()), r.getCnt()))
                .toList();
    }

    private static void requireScope(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            throw new IllegalArgumentException("organizationId and userId are required");
        }
    }
}
