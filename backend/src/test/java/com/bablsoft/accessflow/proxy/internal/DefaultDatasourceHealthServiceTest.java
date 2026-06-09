package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceQueryStats;
import com.bablsoft.accessflow.core.api.DatasourceQueryStatsLookupService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.DatasourcePoolStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDatasourceHealthServiceTest {

    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceConnectionPoolManager poolManager;
    @Mock DatasourceQueryStatsLookupService queryStatsLookupService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC);
    private final UUID orgId = UUID.randomUUID();

    private DefaultDatasourceHealthService service;

    @BeforeEach
    void setUp() {
        service = new DefaultDatasourceHealthService(datasourceAdminService, poolManager,
                queryStatsLookupService, clock, new ConcurrentMapCacheManager());
    }

    private static DatasourceView view(UUID id, UUID org, String name, DbType type, boolean active) {
        return new DatasourceView(id, org, name, type, "host", 5432, "db", "user", SslMode.DISABLE,
                10, 1000, false, false, null, false, null, false, null, null, null, null, null, active,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void stubPage(DatasourceView... views) {
        when(datasourceAdminService.listForAdmin(any(), any()))
                .thenReturn(new PageResponse<>(List.of(views), 0, 50, views.length, 1));
    }

    @Test
    void emptyDatasourceYieldsZeroCountsNullPercentilesAndNullPool() {
        var dsId = UUID.randomUUID();
        stubPage(view(dsId, orgId, "empty-ds", DbType.POSTGRESQL, true));
        when(queryStatsLookupService.statsFor(anyCollection(), any())).thenReturn(Map.of());
        when(poolManager.poolStats(dsId)).thenReturn(Optional.empty());

        var page = service.snapshot(orgId, PageRequest.of(0, 50));

        assertThat(page.content()).hasSize(1);
        var row = page.content().getFirst();
        assertThat(row.datasourceId()).isEqualTo(dsId);
        assertThat(row.datasourceName()).isEqualTo("empty-ds");
        assertThat(row.queriesLast24h()).isZero();
        assertThat(row.errorsLast24h()).isZero();
        assertThat(row.executionMsP50()).isNull();
        assertThat(row.executionMsP95()).isNull();
        assertThat(row.poolActive()).isNull();
        assertThat(row.poolIdle()).isNull();
        assertThat(row.poolWaiting()).isNull();
        assertThat(row.poolTotal()).isNull();
        assertThat(row.poolMax()).isNull();
    }

    @Test
    void failedOnlyDatasourceReportsErrorsAndLivePool() {
        var dsId = UUID.randomUUID();
        stubPage(view(dsId, orgId, "broken-ds", DbType.MYSQL, true));
        when(queryStatsLookupService.statsFor(anyCollection(), any()))
                .thenReturn(Map.of(dsId, new DatasourceQueryStats(8L, 8L, 50.0, 120.0)));
        when(poolManager.poolStats(dsId))
                .thenReturn(Optional.of(new DatasourcePoolStats(1, 0, 4, 1, 10)));

        var row = service.snapshot(orgId, PageRequest.of(0, 50)).content().getFirst();

        assertThat(row.queriesLast24h()).isEqualTo(8L);
        assertThat(row.errorsLast24h()).isEqualTo(8L);
        assertThat(row.executionMsP50()).isEqualTo(50.0);
        assertThat(row.poolActive()).isEqualTo(1);
        assertThat(row.poolWaiting()).isEqualTo(4);
        assertThat(row.poolMax()).isEqualTo(10);
    }

    @Test
    void multiDatasourceOrgPreservesPageOrderAndPerRowState() {
        var dsA = UUID.randomUUID();
        var dsB = UUID.randomUUID();
        stubPage(
                view(dsA, orgId, "ds-a", DbType.POSTGRESQL, true),
                view(dsB, orgId, "ds-b", DbType.ORACLE, false));
        when(queryStatsLookupService.statsFor(anyCollection(), any()))
                .thenReturn(Map.of(dsA, new DatasourceQueryStats(100L, 2L, 10.0, 30.0)));
        when(poolManager.poolStats(dsA))
                .thenReturn(Optional.of(new DatasourcePoolStats(2, 3, 0, 5, 10)));
        when(poolManager.poolStats(dsB)).thenReturn(Optional.empty());

        var content = service.snapshot(orgId, PageRequest.of(0, 50)).content();

        assertThat(content).extracting("datasourceId").containsExactly(dsA, dsB);
        assertThat(content.get(0).queriesLast24h()).isEqualTo(100L);
        assertThat(content.get(0).poolTotal()).isEqualTo(5);
        assertThat(content.get(1).active()).isFalse();
        assertThat(content.get(1).queriesLast24h()).isZero();
        assertThat(content.get(1).poolTotal()).isNull();
        verify(queryStatsLookupService).statsFor(argThatContains(dsA, dsB), any());
    }

    @Test
    void secondCallWithinTtlServesFromCacheWithoutRecomputing() {
        var dsId = UUID.randomUUID();
        when(datasourceAdminService.listForAdmin(any(), any()))
                .thenReturn(new PageResponse<>(List.of(view(dsId, orgId, "ds", DbType.POSTGRESQL, true)),
                        0, 50, 1, 1));
        when(queryStatsLookupService.statsFor(anyCollection(), any()))
                .thenReturn(Map.of(dsId, new DatasourceQueryStats(1L, 0L, 5.0, 9.0)));
        when(poolManager.poolStats(dsId)).thenReturn(Optional.empty());

        service.snapshot(orgId, PageRequest.of(0, 50));
        var second = service.snapshot(orgId, PageRequest.of(0, 50));

        assertThat(second.content()).hasSize(1);
        // listForAdmin runs each call; the per-datasource aggregate is cached.
        verify(datasourceAdminService, times(2)).listForAdmin(any(), any());
        verify(queryStatsLookupService, times(1)).statsFor(anyCollection(), any());
        verify(poolManager, times(1)).poolStats(dsId);
    }

    @Test
    void sameDatasourceIdInDifferentOrgsIsNotCrossServedFromCache() {
        var orgA = UUID.randomUUID();
        var orgB = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        when(datasourceAdminService.listForAdmin(any(), any())).thenReturn(
                new PageResponse<>(List.of(view(dsId, orgA, "ds", DbType.POSTGRESQL, true)), 0, 50, 1, 1));
        when(queryStatsLookupService.statsFor(anyCollection(), any())).thenReturn(Map.of());
        lenient().when(poolManager.poolStats(dsId)).thenReturn(Optional.empty());

        service.snapshot(orgA, PageRequest.of(0, 50));
        service.snapshot(orgB, PageRequest.of(0, 50));

        // Distinct (org, ds) cache keys → recomputed per org, never reused across tenants.
        verify(queryStatsLookupService, times(2)).statsFor(anyCollection(), any());
    }

    private static java.util.Collection<UUID> argThatContains(UUID... ids) {
        return org.mockito.ArgumentMatchers.argThat(c ->
                c != null && c.containsAll(List.of(ids)));
    }
}
