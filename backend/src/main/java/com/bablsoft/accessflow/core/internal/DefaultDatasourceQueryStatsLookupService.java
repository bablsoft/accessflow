package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourceQueryStats;
import com.bablsoft.accessflow.core.api.DatasourceQueryStatsLookupService;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class DefaultDatasourceQueryStatsLookupService implements DatasourceQueryStatsLookupService {

    private final QueryRequestRepository queryRequestRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, DatasourceQueryStats> statsFor(Collection<UUID> datasourceIds, Instant since) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return Map.of();
        }
        var distinctIds = datasourceIds.stream().collect(Collectors.toSet());
        Map<UUID, DatasourceQueryStats> result = new HashMap<>();
        for (var row : queryRequestRepository.aggregateByDatasource(distinctIds, since)) {
            result.put(row.datasourceId(), new DatasourceQueryStats(
                    row.queriesLast24h(),
                    row.errorsLast24h(),
                    row.executionMsP50(),
                    row.executionMsP95()));
        }
        return result;
    }
}
