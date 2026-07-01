package com.bablsoft.accessflow.lifecycle.internal.scheduled;

import com.bablsoft.accessflow.lifecycle.internal.RetentionPolicyExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionPolicyExecutionJobTest {

    @Mock private RetentionPolicyExecutionService executionService;
    @InjectMocks private RetentionPolicyExecutionJob job;

    @Test
    void run_executesEachStagedRun() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(executionService.findStagedRunIds()).thenReturn(List.of(a, b));
        when(executionService.execute(any())).thenReturn(true);

        job.run();

        verify(executionService).execute(a);
        verify(executionService).execute(b);
    }

    @Test
    void run_swallowsPerRunFailure() {
        var a = UUID.randomUUID();
        when(executionService.findStagedRunIds()).thenReturn(List.of(a));
        when(executionService.execute(a)).thenThrow(new RuntimeException("boom"));

        job.run(); // must not propagate
    }

    @Test
    void run_noopWhenNothingStaged() {
        when(executionService.findStagedRunIds()).thenReturn(List.of());
        job.run();
        verify(executionService, never()).execute(any());
    }
}
