package com.bablsoft.accessflow.deploygov.internal.scheduled;

import com.bablsoft.accessflow.deploygov.internal.DefaultDeploymentGateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledDeploymentReleaseJobTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    private DeploymentRequestRepository requestRepository;
    private DefaultDeploymentGateService gateService;
    private ScheduledDeploymentReleaseJob job;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        gateService = mock(DefaultDeploymentGateService.class);
        job = new ScheduledDeploymentReleaseJob(requestRepository, gateService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void scansWithTheInjectedClock() {
        when(requestRepository.findReleasableCandidateIds(NOW)).thenReturn(List.of());

        job.run();

        verify(requestRepository).findReleasableCandidateIds(NOW);
        verify(gateService, never()).markReleasable(any());
    }

    @Test
    void announcesEveryDueRequest() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(requestRepository.findReleasableCandidateIds(NOW)).thenReturn(List.of(first, second));
        when(gateService.markReleasable(any())).thenReturn(true);

        job.run();

        verify(gateService).markReleasable(first);
        verify(gateService).markReleasable(second);
    }

    @Test
    void onePoisonedRowDoesNotAbortTheBatch() {
        var poisoned = UUID.randomUUID();
        var healthy = UUID.randomUUID();
        when(requestRepository.findReleasableCandidateIds(NOW))
                .thenReturn(List.of(poisoned, healthy));
        when(gateService.markReleasable(poisoned)).thenThrow(new IllegalStateException("boom"));
        when(gateService.markReleasable(healthy)).thenReturn(true);

        job.run();

        verify(gateService).markReleasable(healthy);
    }
}
