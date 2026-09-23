package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledJobExecutionAdvisorTest {

    @Mock JobExecutionRecorder recorder;
    @Mock ObjectProvider<JobExecutionRecorder> recorderProvider;
    @Mock ObjectProvider<JobMonitoringProperties> propertiesProvider;
    @Mock ObjectProvider<Environment> environmentProvider;

    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        lenient().when(environmentProvider.getIfAvailable())
                .thenReturn(new MockEnvironment().withProperty("job.lock", "resolvedLock"));
    }

    private void enabled(boolean enabled) {
        when(propertiesProvider.getIfAvailable())
                .thenReturn(new JobMonitoringProperties(enabled, null, null, null, null));
    }

    private <T> T proxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new ScheduledJobExecutionAdvisor(recorderProvider, propertiesProvider, environmentProvider));
        @SuppressWarnings("unchecked")
        var proxy = (T) factory.getProxy();
        return proxy;
    }

    /** Stands in for ShedLock: marks the lock acquired the way JobExecutionLockListener does. */
    private void lockAcquired() {
        JobExecutionContext.current().executionId(executionId);
    }

    @Test
    void isOrderedOutsideShedLockAndTransactions() {
        var advisor = new ScheduledJobExecutionAdvisor(recorderProvider, propertiesProvider, environmentProvider);
        assertThat(advisor.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
        assertThat(advisor.getPointcut().getMethodMatcher()).isNotNull();
    }

    @Test
    void successfulRunIsClosedAsSuccess() {
        enabled(true);
        var job = proxy(new LockedJob(this::lockAcquired, null));

        job.run();

        verify(recorder).close(executionId, null);
        assertThat(JobExecutionContext.current()).isNull();
    }

    @Test
    void lockSkippedTickWritesNothing() {
        enabled(true);
        var job = proxy(new LockedJob(() -> { }, null));

        job.run();

        verifyNoInteractions(recorder);
    }

    @Test
    void failureIsRecordedAndRethrownUnchanged() {
        enabled(true);
        var boom = new IllegalStateException("boom");
        var job = proxy(new LockedJob(this::lockAcquired, boom));

        assertThatThrownBy(job::run).isSameAs(boom);

        verify(recorder).close(same(executionId), same(boom));
        assertThat(JobExecutionContext.current()).isNull();
    }

    @Test
    void recorderFailureDoesNotReachTheJob() {
        enabled(true);
        doThrow(new IllegalStateException("db down")).when(recorder).close(any(), isNull());
        var job = proxy(new LockedJob(this::lockAcquired, null));

        job.run();

        verify(recorder).close(executionId, null);
    }

    @Test
    void recorderFailureDoesNotMaskTheJobsOwnException() {
        enabled(true);
        var boom = new IllegalArgumentException("job failed");
        doThrow(new IllegalStateException("db down")).when(recorder).close(any(), any());
        var job = proxy(new LockedJob(this::lockAcquired, boom));

        assertThatThrownBy(job::run).isSameAs(boom);
    }

    @Test
    void placeholderLockNamesAreResolvedLikeShedLockDoes() {
        enabled(true);
        var seen = new String[1];
        var job = proxy(new PlaceholderLockedJob(() -> seen[0] = JobExecutionContext.current().lockName()));

        job.run();

        assertThat(seen[0]).isEqualTo("resolvedLock");
    }

    @Test
    void lockNameIsKeptRawWithoutAnEnvironment() {
        enabled(true);
        when(environmentProvider.getIfAvailable()).thenReturn(null);
        var seen = new String[1];
        var job = proxy(new PlaceholderLockedJob(() -> seen[0] = JobExecutionContext.current().lockName()));

        job.run();

        assertThat(seen[0]).isEqualTo("${job.lock}");
    }

    @Test
    void unlockedJobOpensItsOwnRow() {
        enabled(true);
        when(recorder.open("UnlockedJob", null)).thenReturn(executionId);
        var job = proxy(new UnlockedJob());

        job.run();

        verify(recorder).open("UnlockedJob", null);
        verify(recorder).close(executionId, null);
    }

    @Test
    void unlockedJobSurvivesAFailingOpen() {
        enabled(true);
        when(recorder.open(any(), any())).thenThrow(new IllegalStateException("db down"));
        var target = new UnlockedJob();

        proxy(target).run();

        assertThat(target.runs).isEqualTo(1);
    }

    @Test
    void disabledRecordingPassesStraightThrough() {
        enabled(false);
        var target = new UnlockedJob();

        proxy(target).run();

        verifyNoInteractions(recorder);
        assertThat(target.runs).isEqualTo(1);
    }

    @Test
    void missingPropertiesPassStraightThrough() {
        when(propertiesProvider.getIfAvailable()).thenReturn(null);
        var job = proxy(new UnlockedJob());

        job.run();

        verifyNoInteractions(recorder);
    }

    @Test
    void nonScheduledMethodsAreNotAdvised() {
        var job = proxy(new UnlockedJob());

        job.helper();

        verifyNoInteractions(recorder, propertiesProvider);
    }

    static class LockedJob {
        private final Runnable body;
        private final RuntimeException failure;

        LockedJob(Runnable body, RuntimeException failure) {
            this.body = body;
            this.failure = failure;
        }

        @Scheduled(fixedDelayString = "PT1M")
        @SchedulerLock(name = "lockedJob", lockAtMostFor = "PT1M")
        public void run() {
            body.run();
            if (failure != null) {
                throw failure;
            }
        }
    }

    static class UnlockedJob {
        int runs;

        @Scheduled(fixedDelayString = "PT1M")
        public void run() {
            runs++;
        }

        public void helper() {
            runs++;
        }
    }

    static class PlaceholderLockedJob {
        private final Runnable body;

        PlaceholderLockedJob(Runnable body) {
            this.body = body;
        }

        @Scheduled(fixedDelayString = "PT1M")
        @SchedulerLock(name = "${job.lock}", lockAtMostFor = "PT1M")
        public void run() {
            body.run();
        }
    }
}
