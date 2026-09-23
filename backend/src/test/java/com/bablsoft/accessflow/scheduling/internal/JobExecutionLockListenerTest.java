package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import net.javacrumbs.shedlock.core.LockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobExecutionLockListenerTest {

    @Mock JobExecutionRecorder recorder;

    private JobExecutionContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.pop();
        }
    }

    private JobExecutionLockListener listener(boolean enabled) {
        return new JobExecutionLockListener(recorder,
                new JobMonitoringProperties(enabled, null, null, null, null));
    }

    private static LockConfiguration lock(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
    }

    @Test
    void opensTheRowWhenTheJobsOwnLockStartsItsTask() {
        var id = UUID.randomUUID();
        when(recorder.open("Job", "jobLock")).thenReturn(id);
        context = JobExecutionContext.push("Job", "jobLock");

        listener(true).onTaskStarted(lock("jobLock"));

        assertThat(context.executionId()).isEqualTo(id);
    }

    @Test
    void ignoresAnotherLocksTask() {
        context = JobExecutionContext.push("Job", "jobLock");

        listener(true).onTaskStarted(lock("otherLock"));

        verifyNoInteractions(recorder);
        assertThat(context.executionId()).isNull();
    }

    @Test
    void ignoresTasksOutsideAScheduledRun() {
        listener(true).onTaskStarted(lock("jobLock"));

        verifyNoInteractions(recorder);
    }

    @Test
    void doesNotReopenOnReentry() {
        context = JobExecutionContext.push("Job", "jobLock");
        var existing = UUID.randomUUID();
        context.executionId(existing);

        listener(true).onTaskStarted(lock("jobLock"));

        verifyNoInteractions(recorder);
        assertThat(context.executionId()).isEqualTo(existing);
    }

    @Test
    void doesNothingWhenRecordingIsDisabled() {
        context = JobExecutionContext.push("Job", "jobLock");

        listener(false).onTaskStarted(lock("jobLock"));

        verifyNoInteractions(recorder);
    }

    @Test
    void swallowsRecorderFailures() {
        when(recorder.open(any(), any())).thenThrow(new IllegalStateException("db down"));
        context = JobExecutionContext.push("Job", "jobLock");

        listener(true).onTaskStarted(lock("jobLock"));

        verify(recorder).open("Job", "jobLock");
        assertThat(context.executionId()).isNull();
    }
}
