package com.bablsoft.accessflow.scheduling.api;

import java.time.Duration;
import java.util.concurrent.Executor;

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

    /**
     * Acquire a cluster-wide lock <em>on the calling thread</em>, then run {@code action} on
     * {@code executor} and release the lock when it finishes.
     *
     * <p>The split matters for request-scoped callers: an endpoint that must answer HTTP 409
     * "already running" synchronously cannot use {@link #runLocked}, which occupies the caller's
     * thread for the whole critical section. Here the acquisition — the part the answer depends on
     * — is synchronous, and only the long work is handed off. The lock provider is not
     * thread-affine, so releasing it on the executor's thread is safe.
     *
     * @param lockName       unique camelCase identifier; shares the namespace with
     *                       {@link #runLocked} and {@code @SchedulerLock}.
     * @param lockAtMostFor  maximum time the lock may be held. It covers the whole handoff, not
     *                       just the queueing, so it must exceed the action's worst-case runtime.
     * @param executor       runs the action. Untouched when the lock is not acquired.
     * @param action         critical section. Its own {@link RuntimeException} is caught and
     *                       logged rather than propagated — by the time it runs, the caller has
     *                       long returned and nothing else can observe it.
     * @return {@code true} when the lock was acquired and the action was handed to {@code executor};
     *         {@code false} when another node holds the lock and nothing was submitted.
     * @throws RuntimeException when {@code executor} refuses the action (for example a rejected
     *         execution on a shut-down executor). The lock is released first.
     */
    boolean runLockedAsync(String lockName, Duration lockAtMostFor, Executor executor,
                           Runnable action);
}
