package com.bablsoft.accessflow.requestgroups.internal.scheduled;

import com.bablsoft.accessflow.requestgroups.internal.RequestGroupStateService;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
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
class GroupReviewEscalationJobTest {

    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");

    @Mock
    private RequestGroupRepository groupRepository;
    @Mock
    private RequestGroupStateService stateService;

    private GroupReviewEscalationJob job;

    @BeforeEach
    void setUp() {
        job = new GroupReviewEscalationJob(groupRepository, stateService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(groupRepository.findEscalationDueIds(any())).thenReturn(List.of());
    }

    @Test
    void scansWithTheInjectedClock() {
        job.run();

        verify(groupRepository).findEscalationDueIds(NOW);
    }

    @Test
    void escalatesDueBundles() {
        var escalate = UUID.randomUUID();
        when(groupRepository.findEscalationDueIds(NOW)).thenReturn(List.of(escalate));
        when(stateService.markEscalated(any(), any())).thenReturn(true);

        job.run();

        verify(stateService).markEscalated(escalate, NOW);
    }

    @Test
    void oneFailingBundleDoesNotAbortTheBatch() {
        var bad = UUID.randomUUID();
        var good = UUID.randomUUID();
        when(groupRepository.findEscalationDueIds(NOW)).thenReturn(List.of(bad, good));
        when(stateService.markEscalated(eq(bad), any())).thenThrow(new IllegalStateException("boom"));
        when(stateService.markEscalated(eq(good), any())).thenReturn(true);

        job.run();

        verify(stateService).markEscalated(good, NOW);
    }

    @Test
    void neverTouchesTheDecisionPath() {
        var id = UUID.randomUUID();
        when(groupRepository.findEscalationDueIds(NOW)).thenReturn(List.of(id));
        when(stateService.markEscalated(any(), any())).thenReturn(true);

        job.run();

        // Escalation is notify-only: idleness must never decide or time out a bundle.
        verify(stateService, never()).markTimedOut(any());
        verify(stateService, never()).apply(any(), any());
    }
}
