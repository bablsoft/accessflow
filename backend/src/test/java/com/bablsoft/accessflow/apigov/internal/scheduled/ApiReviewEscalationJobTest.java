package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.internal.ApiRequestStateService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiReviewEscalationJobTest {

    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");

    @Mock
    private ApiRequestRepository requestRepository;
    @Mock
    private ApiRequestStateService stateService;

    private ApiReviewEscalationJob job;

    @BeforeEach
    void setUp() {
        job = new ApiReviewEscalationJob(requestRepository, stateService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(requestRepository.findEscalationDueIds(any())).thenReturn(List.of());
        when(requestRepository.findNudgeDueIds(any())).thenReturn(List.of());
    }

    @Test
    void scansWithTheInjectedClock() {
        job.run();

        verify(requestRepository).findEscalationDueIds(NOW);
        verify(requestRepository).findNudgeDueIds(NOW);
    }

    @Test
    void escalatesAndNudgesDueRequests() {
        var escalate = UUID.randomUUID();
        var nudge = UUID.randomUUID();
        when(requestRepository.findEscalationDueIds(NOW)).thenReturn(List.of(escalate));
        when(requestRepository.findNudgeDueIds(NOW)).thenReturn(List.of(nudge));
        when(stateService.markEscalated(any(), any())).thenReturn(true);
        when(stateService.markNudged(any(), any())).thenReturn(true);

        job.run();

        verify(stateService).markEscalated(escalate, NOW);
        verify(stateService).markNudged(nudge, NOW);
    }

    @Test
    void oneFailingRowDoesNotAbortTheBatchOrTheNudgePass() {
        var bad = UUID.randomUUID();
        var good = UUID.randomUUID();
        var nudge = UUID.randomUUID();
        when(requestRepository.findEscalationDueIds(NOW)).thenReturn(List.of(bad, good));
        when(requestRepository.findNudgeDueIds(NOW)).thenReturn(List.of(nudge));
        when(stateService.markEscalated(eq(bad), any())).thenThrow(new IllegalStateException("boom"));
        when(stateService.markEscalated(eq(good), any())).thenReturn(true);
        when(stateService.markNudged(any(), any())).thenReturn(true);

        job.run();

        verify(stateService).markEscalated(good, NOW);
        verify(stateService).markNudged(nudge, NOW);
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        job.run();

        verify(stateService, never()).markEscalated(any(), any());
        verify(stateService, never()).markNudged(any(), any());
    }
}
