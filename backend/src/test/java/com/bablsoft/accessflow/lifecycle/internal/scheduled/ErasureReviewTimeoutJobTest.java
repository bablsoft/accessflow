package com.bablsoft.accessflow.lifecycle.internal.scheduled;

import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.internal.ErasureRequestStateService;
import com.bablsoft.accessflow.lifecycle.internal.config.LifecycleProperties;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class ErasureReviewTimeoutJobTest {

    @Mock DeletionRequestRepository requestRepository;
    @Mock ErasureRequestStateService stateService;

    private ErasureReviewTimeoutJob job;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        var props = new LifecycleProperties(null, null, null, null, null);
        job = new ErasureReviewTimeoutJob(requestRepository, stateService, props, clock);
    }

    @Test
    void run_marksEachTimedOutRequest() {
        var a = UUID.randomUUID();
        when(requestRepository.findTimedOutPendingReviewIds(eq(ErasureStatus.PENDING_REVIEW), any()))
                .thenReturn(List.of(a));
        when(stateService.markTimedOut(a)).thenReturn(true);

        job.run();

        verify(stateService).markTimedOut(a);
    }

    @Test
    void run_swallowsPerRowFailure() {
        var a = UUID.randomUUID();
        when(requestRepository.findTimedOutPendingReviewIds(eq(ErasureStatus.PENDING_REVIEW), any()))
                .thenReturn(List.of(a));
        when(stateService.markTimedOut(a)).thenThrow(new RuntimeException("boom"));

        job.run(); // must not propagate
    }

    @Test
    void run_noopWhenNothingTimedOut() {
        when(requestRepository.findTimedOutPendingReviewIds(eq(ErasureStatus.PENDING_REVIEW), any()))
                .thenReturn(List.of());
        job.run();
        verify(stateService, never()).markTimedOut(any());
    }
}
