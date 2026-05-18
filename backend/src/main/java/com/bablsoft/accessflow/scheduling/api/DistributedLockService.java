package com.bablsoft.accessflow.scheduling.api;

import java.time.Duration;

/**
 * Cluster-wide programmatic lock. Used for one-shot critical sections that need to run on at
 * most one replica per acquisition window — e.g. the startup bootstrap reconciliation — where
 * the periodic {@code @SchedulerLock} annotation does not fit.
 *
 * <p>Backed by the same Redis instance that powers ShedLock and the JWT refresh-token store;
 * the underlying lock-provider type is intentionally hidden so this module's {@code api/}
 * package stays free of third-party imports.
 */
public interface DistributedLockService {

    /**
     * Run {@code action} under a cluster-wide lock identified by {@code lockName}.
     *
     * @param lockName       unique camelCase identifier; collides with any other caller using the
     *                       same name, including {@code @SchedulerLock} jobs.
     * @param lockAtMostFor  maximum time the lock may be held — the underlying Redis key expires
     *                       after this duration even if this JVM crashes mid-action, so set it
     *                       generously above the expected action duration.
     * @param action         critical section. Runs on the calling thread when the lock is
     *                       acquired; any {@link RuntimeException} propagates after the lock is
     *                       released.
     * @return {@code true} when the action executed (lock acquired); {@code false} when another
     *         node holds the lock and the action was skipped.
     */
    boolean runLocked(String lockName, Duration lockAtMostFor, Runnable action);
}
