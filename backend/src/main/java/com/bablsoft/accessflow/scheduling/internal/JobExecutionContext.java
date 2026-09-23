package com.bablsoft.accessflow.scheduling.internal;

import java.util.UUID;

/**
 * Per-thread handoff between {@link ScheduledJobExecutionAdvisor} (outside the ShedLock advice) and
 * {@link JobExecutionLockListener} (called by ShedLock only once the lock is held). ShedLock
 * acquires the lock and runs the task on the calling thread, so a {@code ThreadLocal} is enough.
 * An execution id that is still {@code null} when the method returns means the lock was skipped.
 */
final class JobExecutionContext {

    private static final ThreadLocal<JobExecutionContext> CURRENT = new ThreadLocal<>();

    private final String jobName;
    private final String lockName;
    private final JobExecutionContext previous;
    private UUID executionId;

    private JobExecutionContext(String jobName, String lockName, JobExecutionContext previous) {
        this.jobName = jobName;
        this.lockName = lockName;
        this.previous = previous;
    }

    static JobExecutionContext push(String jobName, String lockName) {
        var context = new JobExecutionContext(jobName, lockName, CURRENT.get());
        CURRENT.set(context);
        return context;
    }

    static JobExecutionContext current() {
        return CURRENT.get();
    }

    void pop() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    String jobName() {
        return jobName;
    }

    String lockName() {
        return lockName;
    }

    UUID executionId() {
        return executionId;
    }

    void executionId(UUID executionId) {
        this.executionId = executionId;
    }
}
