package com.bablsoft.accessflow.discovery.internal.scheduled;

import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import com.bablsoft.accessflow.discovery.internal.DiscoveryScanService;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryScanJobTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    @Mock
    private DiscoveryScanConfigRepository configRepository;
    @Mock
    private DiscoveryScanService scanService;

    private DiscoveryScanJob job() {
        return new DiscoveryScanJob(configRepository, scanService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DiscoveryScanConfigEntity config(Instant lastScanAt, int intervalHours) {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(UUID.randomUUID());
        config.setDatasourceId(UUID.randomUUID());
        config.setEnabled(true);
        config.setScanIntervalHours(intervalHours);
        config.setLastScanAt(lastScanAt);
        return config;
    }

    @Test
    void scansNeverScannedAndOverdueConfigsOnly() {
        var neverScanned = config(null, 24);
        var overdue = config(NOW.minus(Duration.ofHours(25)), 24);
        var fresh = config(NOW.minus(Duration.ofHours(1)), 24);
        when(configRepository.findAllByEnabledTrue())
                .thenReturn(List.of(neverScanned, overdue, fresh));

        job().run();

        verify(scanService).scan(eq(neverScanned.getDatasourceId()),
                eq(neverScanned.getOrganizationId()), isNull());
        verify(scanService).scan(eq(overdue.getDatasourceId()),
                eq(overdue.getOrganizationId()), isNull());
        verify(scanService, never()).scan(eq(fresh.getDatasourceId()), any(), any());
    }

    @Test
    void perDatasourceFailureDoesNotAbortBatch() {
        var first = config(null, 24);
        var second = config(null, 24);
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("boom")).when(scanService)
                .scan(eq(first.getDatasourceId()), any(), isNull());

        job().run();

        verify(scanService).scan(eq(second.getDatasourceId()),
                eq(second.getOrganizationId()), isNull());
    }

    @Test
    void alreadyRunningScanIsSkippedQuietly() {
        var first = config(null, 24);
        var second = config(null, 24);
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(first, second));
        doThrow(new DiscoveryScanAlreadyRunningException(first.getDatasourceId()))
                .when(scanService).scan(eq(first.getDatasourceId()), any(), isNull());

        job().run();

        verify(scanService).scan(eq(second.getDatasourceId()),
                eq(second.getOrganizationId()), isNull());
    }

    @Test
    void noDueConfigsDoesNothing() {
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of());

        job().run();

        verify(scanService, never()).scan(any(), any(), any());
    }
}
