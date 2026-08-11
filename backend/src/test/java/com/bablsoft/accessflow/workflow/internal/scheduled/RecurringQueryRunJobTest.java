package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringQueryRunJobTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryLifecycleService queryLifecycleService;

    private RecurringQueryRunJob job;

    @BeforeEach
    void setUp() {
        job = new RecurringQueryRunJob(queryRequestLookupService, queryLifecycleService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void runDoesNothingWhenNoSeriesAreDue() {
        when(queryRequestLookupService.findRecurringDueIds(NOW)).thenReturn(List.of());

        job.run();

        verify(queryRequestLookupService).findRecurringDueIds(NOW);
        verify(queryLifecycleService, never()).executeRecurringOccurrence(any());
    }

    @Test
    void runFiresExecuteRecurringOccurrenceForEachReturnedId() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        when(queryRequestLookupService.findRecurringDueIds(NOW)).thenReturn(List.of(id1, id2));

        job.run();

        verify(queryLifecycleService).executeRecurringOccurrence(id1);
        verify(queryLifecycleService).executeRecurringOccurrence(id2);
    }

    @Test
    void runContinuesAfterPerRowFailure() {
        var failing = UUID.randomUUID();
        var ok = UUID.randomUUID();
        when(queryRequestLookupService.findRecurringDueIds(NOW))
                .thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("boom"))
                .when(queryLifecycleService).executeRecurringOccurrence(failing);

        job.run();

        verify(queryLifecycleService, times(2)).executeRecurringOccurrence(any(UUID.class));
        verify(queryLifecycleService).executeRecurringOccurrence(ok);
    }
}
