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
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class DefaultDatasourceHealthService implements DatasourceHealthService {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceConnectionPoolManager poolManager;
    private final DatasourceQueryStatsLookupService queryStatsLookupService;
    private final Clock clock;
    private final CacheManager cacheManager;

    DefaultDatasourceHealthService(DatasourceAdminService datasourceAdminService,
                                   DatasourceConnectionPoolManager poolManager,
                                   DatasourceQueryStatsLookupService queryStatsLookupService,
                                   Clock proxyClock,
                                   CacheManager cacheManager) {
        this.datasourceAdminService = datasourceAdminService;
        this.poolManager = poolManager;
        this.queryStatsLookupService = queryStatsLookupService;
        this.clock = proxyClock;
        this.cacheManager = cacheManager;
    }

    @Override
    public PageResponse<DatasourceHealthSnapshot> snapshot(UUID organizationId,
                                                           PageRequest pageRequest) {
        var page = datasourceAdminService.listForAdmin(organizationId, pageRequest);
        var cache = cacheManager.getCache(ProxyConfiguration.DATASOURCE_HEALTH_CACHE);

        // Serve cache hits per (org, datasource); collect the misses so a single batched stats
        // query fills them all (no N+1). The org is part of the key, so snapshots are never
        // cross-served between tenants.
        Map<UUID, DatasourceHealthSnapshot> resolved = new HashMap<>();
        List<DatasourceView> misses = new ArrayList<>();
        for (var view : page.content()) {
            var cached = cache.get(new HealthKey(organizationId, view.id()),
                    DatasourceHealthSnapshot.class);
            if (cached != null) {
                resolved.put(view.id(), cached);
            } else {
                misses.add(view);
            }
        }

        if (!misses.isEmpty()) {
            var missIds = misses.stream().map(DatasourceView::id).toList();
            var stats = queryStatsLookupService.statsFor(missIds, clock.instant().minus(WINDOW));
            for (var view : misses) {
                var pool = poolManager.poolStats(view.id()).orElse(null);
                var queryStats = stats.getOrDefault(view.id(), DatasourceQueryStats.empty());
                var snapshot = toSnapshot(view, pool, queryStats);
                cache.put(new HealthKey(organizationId, view.id()), snapshot);
                resolved.put(view.id(), snapshot);
            }
        }

        var content = page.content().stream()
                .map(view -> resolved.get(view.id()))
                .toList();
        return new PageResponse<>(content, page.page(), page.size(),
                page.totalElements(), page.totalPages());
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
