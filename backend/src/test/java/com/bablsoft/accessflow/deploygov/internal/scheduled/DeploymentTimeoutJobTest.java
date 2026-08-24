package com.bablsoft.accessflow.deploygov.internal.scheduled;

import com.bablsoft.accessflow.deploygov.internal.DeploymentRequestStateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeploymentTimeoutJobTest {

    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    @Mock
    private DeploymentRequestRepository requestRepository;
    @Mock
    private DeploymentRequestStateService stateService;

    private DeploymentTimeoutJob job;

    @BeforeEach
    void setUp() {
        job = new DeploymentTimeoutJob(requestRepository, stateService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(requestRepository.findStalePendingReviewIds(any())).thenReturn(List.of());
    }

    @Test
    void scansWithTheInjectedClock() {
        job.run();

        verify(requestRepository).findStalePendingReviewIds(NOW);
    }

    @Test
    void timesOutEveryDueRequest() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(requestRepository.findStalePendingReviewIds(NOW)).thenReturn(List.of(first, second));
        when(stateService.markTimedOut(any())).thenReturn(true);

        job.run();

        verify(stateService).markTimedOut(first);
        verify(stateService).markTimedOut(second);
    }

    @Test
    void oneFailingRowDoesNotAbortTheBatch() {
        var bad = UUID.randomUUID();
        var good = UUID.randomUUID();
        when(requestRepository.findStalePendingReviewIds(NOW)).thenReturn(List.of(bad, good));
        when(stateService.markTimedOut(bad)).thenThrow(new IllegalStateException("boom"));
        when(stateService.markTimedOut(good)).thenReturn(true);

        job.run();

        verify(stateService).markTimedOut(good);
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        job.run();

        verify(stateService, never()).markTimedOut(any());
    }
}
