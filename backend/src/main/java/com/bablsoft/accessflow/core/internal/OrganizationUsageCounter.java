package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Single source of truth for per-org resource counts (AF-456). Shared by {@code DefaultQuotaService}
 * (enforcement) and {@code DefaultOrganizationAdminService} (usage reporting) so the two never drift.
 */
@Component
@RequiredArgsConstructor
class OrganizationUsageCounter {

    static final Duration QUERY_WINDOW = Duration.ofHours(24);

    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final QueryRequestRepository queryRequestRepository;
    private final Clock clock;

    long datasourceCount(UUID organizationId) {
        return datasourceRepository.countByOrganization_Id(organizationId);
    }

    long activeUserCount(UUID organizationId) {
        return userRepository.countByOrganization_IdAndActiveTrue(organizationId);
    }

    long queriesLast24h(UUID organizationId) {
        return queryRequestRepository.countByOrganizationSince(
                organizationId, clock.instant().minus(QUERY_WINDOW));
    }
}
