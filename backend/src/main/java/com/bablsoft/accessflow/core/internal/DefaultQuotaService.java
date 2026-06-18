package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.OrganizationNotFoundException;
import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.QuotaType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.ToLongFunction;

@Service
@RequiredArgsConstructor
class DefaultQuotaService implements QuotaService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationUsageCounter usageCounter;

    @Override
    @Transactional(readOnly = true)
    public void checkDatasourceQuota(UUID organizationId) {
        enforce(organizationId, QuotaType.DATASOURCE,
                OrganizationEntity::getMaxDatasources, usageCounter::datasourceCount);
    }

    @Override
    @Transactional(readOnly = true)
    public void checkUserQuota(UUID organizationId) {
        enforce(organizationId, QuotaType.USER,
                OrganizationEntity::getMaxUsers, usageCounter::activeUserCount);
    }

    @Override
    @Transactional(readOnly = true)
    public void checkQueryQuota(UUID organizationId) {
        enforce(organizationId, QuotaType.QUERIES_PER_DAY,
                OrganizationEntity::getMaxQueriesPerDay, usageCounter::queriesLast24h);
    }

    private void enforce(UUID organizationId, QuotaType type,
                         java.util.function.Function<OrganizationEntity, Integer> limitAccessor,
                         ToLongFunction<UUID> counter) {
        var organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException(organizationId));
        var limit = limitAccessor.apply(organization);
        if (limit == null || limit <= 0) {
            return; // null/0 = unlimited
        }
        var current = counter.applyAsLong(organizationId);
        if (current >= limit) {
            throw new QuotaExceededException(type, organizationId, limit, current);
        }
    }
}
