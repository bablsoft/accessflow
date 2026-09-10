package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultDistributedLockService implements DistributedLockService {

    private final LockProvider lockProvider;

    @Override
    public boolean runLocked(String lockName, Duration lockAtMostFor, Runnable action) {
        Optional<SimpleLock> acquired = acquire(lockName, lockAtMostFor);
        if (acquired.isEmpty()) {
            return false;
        }
        SimpleLock lock = acquired.get();
        try {
            action.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean runLockedAsync(String lockName, Duration lockAtMostFor, Executor executor,
                                  Runnable action) {
        Optional<SimpleLock> acquired = acquire(lockName, lockAtMostFor);
        if (acquired.isEmpty()) {
            return false;
        }
        SimpleLock lock = acquired.get();
        // A same-thread Executor both runs the task and can throw out of execute(), so "unlock in
        // the task, or in the catch" is not by itself exclusive. The guard makes it so.
        var released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                lock.unlock();
            }
        };
        try {
            executor.execute(() -> runAndRelease(lockName, release, action));
            return true;
        } catch (RuntimeException ex) {
            release.run();
            throw ex;
        }
    }

    private void runAndRelease(String lockName, Runnable release, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            // The caller returned as soon as the lock was acquired, so nothing downstream can
            // observe this; letting it escape would print a bare executor stack trace instead.
            log.error("Action under distributed lock '{}' failed", lockName, ex);
        } finally {
            release.run();
        }
    }

    private Optional<SimpleLock> acquire(String lockName, Duration lockAtMostFor) {
        LockConfiguration config = new LockConfiguration(
                Instant.now(), lockName, lockAtMostFor, Duration.ZERO);
        Optional<SimpleLock> acquired = lockProvider.lock(config);
        if (acquired.isEmpty()) {
            log.debug("Distributed lock '{}' is held by another node; skipping action", lockName);
        }
        return acquired;
    }
}
