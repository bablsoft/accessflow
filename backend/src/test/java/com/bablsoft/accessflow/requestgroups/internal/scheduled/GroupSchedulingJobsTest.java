package com.bablsoft.accessflow.requestgroups.internal.scheduled;

import com.bablsoft.accessflow.requestgroups.internal.GroupExecutionService;
import com.bablsoft.accessflow.requestgroups.internal.RequestGroupStateService;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupSchedulingJobsTest {

    @Mock
    private RequestGroupRepository repository;

    @Test
    void scheduledRunJobExecutesEachDueGroupAndSwallowsPerRowErrors() {
        var execution = org.mockito.Mockito.mock(GroupExecutionService.class);
        var job = new ScheduledGroupRunJob(repository, execution);
        var good = UUID.randomUUID();
        var bad = UUID.randomUUID();
        when(repository.findScheduledDueIds(any())).thenReturn(List.of(bad, good));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(execution)
                .execute(eq(bad), any(), eq("scheduled"));

        job.run();

        verify(execution).execute(eq(bad), any(), eq("scheduled"));
        verify(execution).execute(eq(good), any(), eq("scheduled"));
    }

    @Test
    void scheduledRunJobNoOpsWhenNothingDue() {
        var execution = org.mockito.Mockito.mock(GroupExecutionService.class);
        var job = new ScheduledGroupRunJob(repository, execution);
        when(repository.findScheduledDueIds(any())).thenReturn(List.of());

        job.run();

        verify(execution, never()).execute(any(), any(), any());
    }

    @Test
    void timeoutJobMarksStaleGroupsTimedOut() {
        var state = org.mockito.Mockito.mock(RequestGroupStateService.class);
        var job = new GroupTimeoutJob(repository, state);
        ReflectionTestUtils.setField(job, "approvalTimeout", Duration.ofHours(24));
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repository.findStalePendingReviewIds(any(Instant.class))).thenReturn(List.of(a, b));
        when(state.markTimedOut(a)).thenReturn(true);
        when(state.markTimedOut(b)).thenThrow(new RuntimeException("boom"));

        job.run();

        verify(state).markTimedOut(a);
        verify(state).markTimedOut(b);
    }

    @Test
    void timeoutJobNoOpsWhenNoneStale() {
        var state = org.mockito.Mockito.mock(RequestGroupStateService.class);
        var job = new GroupTimeoutJob(repository, state);
        ReflectionTestUtils.setField(job, "approvalTimeout", Duration.ofHours(24));
        when(repository.findStalePendingReviewIds(any(Instant.class))).thenReturn(List.of());

        job.run();

        verify(state, never()).markTimedOut(any());
    }
}
