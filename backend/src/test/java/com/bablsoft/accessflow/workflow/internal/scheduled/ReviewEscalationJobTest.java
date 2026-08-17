package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
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
class ReviewEscalationJobTest {

    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");

    @Mock
    private QueryRequestLookupService lookupService;
    @Mock
    private QueryRequestStateService stateService;

    private ReviewEscalationJob job;

    @BeforeEach
    void setUp() {
        job = new ReviewEscalationJob(lookupService, stateService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(lookupService.findEscalationDueIds(any())).thenReturn(List.of());
        when(lookupService.findNudgeDueIds(any())).thenReturn(List.of());
    }

    @Test
    void scansWithTheInjectedClockNotWallTime() {
        // The pattern requires an injected Clock precisely so "is it due yet" stays testable —
        // QueryTimeoutJob and ScheduledQueryRunJob both use Instant.now() and cannot be.
        job.run();

        verify(lookupService).findEscalationDueIds(NOW);
        verify(lookupService).findNudgeDueIds(NOW);
    }

    @Test
    void escalatesEveryDueRequest() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(lookupService.findEscalationDueIds(NOW)).thenReturn(List.of(first, second));
        when(stateService.markEscalated(any(), any())).thenReturn(true);

        job.run();

        verify(stateService).markEscalated(first, NOW);
        verify(stateService).markEscalated(second, NOW);
    }

    @Test
    void nudgesEveryDueRequest() {
        var id = UUID.randomUUID();
        when(lookupService.findNudgeDueIds(NOW)).thenReturn(List.of(id));
        when(stateService.markNudged(any(), any())).thenReturn(true);

        job.run();

        verify(stateService).markNudged(id, NOW);
    }

    @Test
    void oneFailingRowDoesNotAbortTheBatch() {
        var bad = UUID.randomUUID();
        var good = UUID.randomUUID();
        when(lookupService.findEscalationDueIds(NOW)).thenReturn(List.of(bad, good));
        when(stateService.markEscalated(eq(bad), any())).thenThrow(new IllegalStateException("boom"));
        when(stateService.markEscalated(eq(good), any())).thenReturn(true);

        job.run();

        // The rest of the queue still needs its warning; the next tick retries the bad row.
        verify(stateService).markEscalated(good, NOW);
    }

    @Test
    void aFailedEscalationDoesNotStopTheNudgePass() {
        var bad = UUID.randomUUID();
        var nudged = UUID.randomUUID();
        when(lookupService.findEscalationDueIds(NOW)).thenReturn(List.of(bad));
        when(lookupService.findNudgeDueIds(NOW)).thenReturn(List.of(nudged));
        when(stateService.markEscalated(any(), any())).thenThrow(new IllegalStateException("boom"));
        when(stateService.markNudged(any(), any())).thenReturn(true);

        job.run();

        verify(stateService).markNudged(nudged, NOW);
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        job.run();

        verify(stateService, never()).markEscalated(any(), any());
        verify(stateService, never()).markNudged(any(), any());
    }

    @Test
    void neverTouchesTheDecisionPath() {
        var id = UUID.randomUUID();
        when(lookupService.findEscalationDueIds(NOW)).thenReturn(List.of(id));
        when(stateService.markEscalated(any(), any())).thenReturn(true);

        job.run();

        // Escalation is notify-only: idleness must never approve, reject, or time out a request.
        verify(stateService, never()).markTimedOut(any());
        verify(stateService, never()).recordApprovalAndAdvance(any());
        verify(stateService, never()).recordRejection(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }
}
