package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryTimeoutJobTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryRequestStateService queryRequestStateService;
    @InjectMocks QueryTimeoutJob job;

    @Test
    void runDoesNothingWhenNoTimeouts() {
        when(queryRequestLookupService.findTimedOutPendingReviewIds(any(Instant.class)))
                .thenReturn(List.of());

        job.run();

        verify(queryRequestStateService, never()).markTimedOut(any());
    }

    @Test
    void runCallsMarkTimedOutForEachReturnedId() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        when(queryRequestLookupService.findTimedOutPendingReviewIds(any(Instant.class)))
                .thenReturn(List.of(id1, id2));
        when(queryRequestStateService.markTimedOut(any(UUID.class))).thenReturn(true);

        job.run();

        verify(queryRequestStateService).markTimedOut(id1);
        verify(queryRequestStateService).markTimedOut(id2);
    }

    @Test
    void runContinuesAfterPerRowFailure() {
        var failing = UUID.randomUUID();
        var ok1 = UUID.randomUUID();
        var ok2 = UUID.randomUUID();
        when(queryRequestLookupService.findTimedOutPendingReviewIds(any(Instant.class)))
                .thenReturn(List.of(failing, ok1, ok2));
        when(queryRequestStateService.markTimedOut(failing))
                .thenThrow(new RuntimeException("boom"));
        when(queryRequestStateService.markTimedOut(ok1)).thenReturn(true);
        when(queryRequestStateService.markTimedOut(ok2)).thenReturn(true);

        job.run();

        verify(queryRequestStateService, times(3)).markTimedOut(any(UUID.class));
    }

    @Test
    void runHandlesIdsThatAreAlreadyTransitioned() {
        var id = UUID.randomUUID();
        when(queryRequestLookupService.findTimedOutPendingReviewIds(any(Instant.class)))
                .thenReturn(List.of(id));
        when(queryRequestStateService.markTimedOut(id)).thenReturn(false);

        job.run();

        verify(queryRequestStateService).markTimedOut(id);
    }
}
