package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutorListener;
import org.springframework.stereotype.Component;

/**
 * Opens the {@code RUNNING} row once ShedLock has actually acquired a job's lock.
 *
 * <p>ShedLock's method-proxy advice resolves this bean and calls {@link #onTaskStarted} on the
 * executing thread only after the lock is held, so a lock-skipped tick never reaches it. That is why
 * this hook is used instead of decorating the {@code LockProvider}: a decorator would also see the
 * programmatic {@code DistributedLockService} locks jobs take inside their body, which bypass
 * ShedLock's executor and therefore never reach this listener.
 *
 * <p>ShedLock resolves exactly one {@code LockingTaskExecutorListener} bean
 * ({@code ObjectProvider#getIfAvailable}). A second listener bean — ShedLock's Micrometer
 * integration, say — would make every {@code @SchedulerLock} job fail with
 * {@code NoUniqueBeanDefinitionException}; compose it into this one instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class JobExecutionLockListener implements LockingTaskExecutorListener {

    private final JobExecutionRecorder recorder;
    private final JobMonitoringProperties properties;

    @Override
    public void onTaskStarted(LockConfiguration lockConfig) {
        var context = JobExecutionContext.current();
        if (!properties.isEnabled() || context == null || context.executionId() != null
                || !lockConfig.getName().equals(context.lockName())) {
            return;
        }
        try {
            context.executionId(recorder.open(context.jobName(), context.lockName()));
        } catch (RuntimeException ex) {
            log.warn("Could not record the start of scheduled job {}", context.jobName(), ex);
        }
    }
}
