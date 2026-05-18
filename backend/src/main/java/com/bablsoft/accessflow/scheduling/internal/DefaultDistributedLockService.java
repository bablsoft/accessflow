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

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultDistributedLockService implements DistributedLockService {

    private final LockProvider lockProvider;

    @Override
    public boolean runLocked(String lockName, Duration lockAtMostFor, Runnable action) {
        LockConfiguration config = new LockConfiguration(
                Instant.now(), lockName, lockAtMostFor, Duration.ZERO);
        Optional<SimpleLock> acquired = lockProvider.lock(config);
        if (acquired.isEmpty()) {
            log.debug("Distributed lock '{}' is held by another node; skipping action", lockName);
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
}
