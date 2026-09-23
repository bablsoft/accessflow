package com.bablsoft.accessflow.scheduling.internal.scheduled;

import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobExecutionRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Mock JobExecutionRepository repository;

    private JobExecutionRetentionJob job(Duration retention, Integer maxPerJob) {
        return new JobExecutionRetentionJob(repository,
                new JobMonitoringProperties(true, retention, maxPerJob, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void prunesByAgeAndTrimsEachJob() {
        job(Duration.ofDays(14), 500).run();

        verify(repository).deleteStartedBefore(NOW.minus(Duration.ofDays(14)));
        verify(repository).trimPerJob(500);
    }

    @Test
    void nonPositiveRetentionSkipsAgePruningButStillCaps() {
        job(Duration.ZERO, 100).run();
        job(Duration.ofDays(-1), 100).run();

        verify(repository, never()).deleteStartedBefore(any());
        verify(repository, org.mockito.Mockito.times(2)).trimPerJob(100);
    }

    @Test
    void nonPositiveCapSkipsTrimming() {
        job(Duration.ofDays(1), 0).run();

        verify(repository).deleteStartedBefore(NOW.minus(Duration.ofDays(1)));
        verify(repository, never()).trimPerJob(anyInt());
    }

    @Test
    void oneAxisFailingDoesNotStopTheOtherButTheRunFails() {
        var first = new IllegalStateException("age");
        when(repository.deleteStartedBefore(any())).thenThrow(first);
        when(repository.trimPerJob(anyInt())).thenThrow(new IllegalStateException("cap"));

        assertThatThrownBy(() -> job(Duration.ofDays(1), 10).run()).isSameAs(first);

        verify(repository).trimPerJob(10);
    }

    @Test
    void capFailureAloneFailsTheRun() {
        var cap = new IllegalStateException("cap");
        when(repository.trimPerJob(anyInt())).thenThrow(cap);

        assertThatThrownBy(() -> job(Duration.ofDays(1), 10).run()).isSameAs(cap);
    }

    @Test
    void isScheduledAndLocked() throws Exception {
        var method = JobExecutionRetentionJob.class.getMethod("run");
        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString())
                .isEqualTo("${accessflow.scheduling.executions.retention-poll-interval:PT6H}");
        assertThat(method.getAnnotation(SchedulerLock.class).name()).isEqualTo("jobExecutionRetentionJob");
    }
}
