package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledQueryRunJobTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryLifecycleService queryLifecycleService;
    @InjectMocks ScheduledQueryRunJob job;

    @Test
    void runDoesNothingWhenNoQueriesAreDue() {
        when(queryRequestLookupService.findScheduledDueIds(any(Instant.class)))
                .thenReturn(List.of());

        job.run();

        verify(queryLifecycleService, never()).executeScheduled(any());
    }

    @Test
    void runFiresExecuteScheduledForEachReturnedId() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        when(queryRequestLookupService.findScheduledDueIds(any(Instant.class)))
                .thenReturn(List.of(id1, id2));

        job.run();

        verify(queryLifecycleService).executeScheduled(id1);
        verify(queryLifecycleService).executeScheduled(id2);
    }

    @Test
    void runContinuesAfterPerRowFailure() {
        var failing = UUID.randomUUID();
        var ok1 = UUID.randomUUID();
        var ok2 = UUID.randomUUID();
        when(queryRequestLookupService.findScheduledDueIds(any(Instant.class)))
                .thenReturn(List.of(failing, ok1, ok2));
        doThrow(new RuntimeException("boom")).when(queryLifecycleService).executeScheduled(failing);

        job.run();

        verify(queryLifecycleService, times(3)).executeScheduled(any(UUID.class));
    }
}
