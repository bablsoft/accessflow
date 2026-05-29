package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceQueryStats;
import com.bablsoft.accessflow.core.api.DatasourceQueryStatsLookupService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.DatasourceHealthService;
import com.bablsoft.accessflow.proxy.api.DatasourceHealthSnapshot;
import com.bablsoft.accessflow.proxy.api.DatasourcePoolStats;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class DefaultDatasourceHealthService implements DatasourceHealthService {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceConnectionPoolManager poolManager;
    private final DatasourceQueryStatsLookupService queryStatsLookupService;
    private final Clock clock;
    private final Cache<HealthKey, DatasourceHealthSnapshot> cache;

    DefaultDatasourceHealthService(DatasourceAdminService datasourceAdminService,
                                   DatasourceConnectionPoolManager poolManager,
                                   DatasourceQueryStatsLookupService queryStatsLookupService,
                                   Clock proxyClock,
                                   ProxyHealthProperties properties) {
        this.datasourceAdminService = datasourceAdminService;
        this.poolManager = poolManager;
        this.queryStatsLookupService = queryStatsLookupService;
        this.clock = proxyClock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .build();
    }

    @Override
    public PageResponse<DatasourceHealthSnapshot> snapshot(UUID organizationId,
                                                           PageRequest pageRequest) {
        var page = datasourceAdminService.listForAdmin(organizationId, pageRequest);
        var viewsById = page.content().stream()
                .collect(Collectors.toMap(DatasourceView::id, Function.identity()));
        var keys = page.content().stream()
                .map(view -> new HealthKey(organizationId, view.id()))
                .toList();
        var resolved = cache.getAll(keys, missing -> computeBatch(missing, viewsById));
        var content = page.content().stream()
                .map(view -> resolved.get(new HealthKey(organizationId, view.id())))
                .toList();
        return new PageResponse<>(content, page.page(), page.size(),
                page.totalElements(), page.totalPages());
    }

    private Map<HealthKey, DatasourceHealthSnapshot> computeBatch(
            Set<? extends HealthKey> missing, Map<UUID, DatasourceView> viewsById) {
        var missingIds = missing.stream().map(HealthKey::datasourceId).collect(Collectors.toSet());
        var stats = queryStatsLookupService.statsFor(missingIds, clock.instant().minus(WINDOW));
        Map<HealthKey, DatasourceHealthSnapshot> computed = new HashMap<>();
        for (var key : missing) {
            var view = viewsById.get(key.datasourceId());
            var pool = poolManager.poolStats(key.datasourceId()).orElse(null);
            var queryStats = stats.getOrDefault(key.datasourceId(), DatasourceQueryStats.empty());
            computed.put(key, toSnapshot(view, pool, queryStats));
        }
        return computed;
    }

    private static DatasourceHealthSnapshot toSnapshot(DatasourceView view, DatasourcePoolStats pool,
                                                       DatasourceQueryStats stats) {
        return new DatasourceHealthSnapshot(
                view.id(),
                view.name(),
                view.dbType(),
                view.active(),
                pool == null ? null : pool.active(),
                pool == null ? null : pool.idle(),
                pool == null ? null : pool.waiting(),
                pool == null ? null : pool.total(),
                pool == null ? null : pool.max(),
                stats.queriesLast24h(),
                stats.executionMsP50(),
                stats.executionMsP95(),
                stats.errorsLast24h());
    }

    private record HealthKey(UUID organizationId, UUID datasourceId) {
    }
}
