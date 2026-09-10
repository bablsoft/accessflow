package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock against real Redis, not a mocked {@code LockProvider} (AF-660). Everything else about
 * the discovery scan guard is unit-tested, but three properties are only true of the deployed
 * provider: that a second acquisition of the same name is actually refused, that a release really
 * frees the key, and that {@code runLockedAsync} can acquire on one thread and release on another
 * — which {@code safeUpdate(true)}'s compare-and-delete Lua has to tolerate.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DistributedLockServiceIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired
    private DistributedLockService lockService;

    private String uniqueLockName() {
        return "distributedLockIntegrationTest:" + UUID.randomUUID();
    }

    @Test
    void secondAcquisitionIsRefusedWhileTheFirstStillHoldsTheLock() throws Exception {
        var lockName = uniqueLockName();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAttempt = new ArrayBlockingQueue<Boolean>(1);

        try (ExecutorService holder = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.execute(() -> lockService.runLocked(lockName, TTL, () -> {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            secondAttempt.add(lockService.runLocked(lockName, TTL, () -> { }));
            release.countDown();
        }

        assertThat(secondAttempt.take()).isFalse();
        // Released, so the name is free again — the lock is not sticky past the action.
        assertThat(lockService.runLocked(lockName, TTL, () -> { })).isTrue();
    }

    @Test
    void runLockedAsyncAcquiresOnOneThreadAndReleasesOnAnother() throws Exception {
        var lockName = uniqueLockName();
        var queue = new ArrayBlockingQueue<Runnable>(1);
        Executor deferred = queue::add;
        var ran = new AtomicBoolean();

        boolean started = lockService.runLockedAsync(lockName, TTL, deferred, () -> ran.set(true));

        assertThat(started).isTrue();
        // Still held while the action sits in the queue: this is what lets the trigger endpoint
        // answer 409 instead of accepting a scan that would double up on the customer database.
        assertThat(lockService.runLocked(lockName, TTL, () -> { })).isFalse();

        var task = queue.take();
        try (ExecutorService other = Executors.newVirtualThreadPerTaskExecutor()) {
            other.execute(task);
        }

        assertThat(ran).isTrue();
        assertThat(lockService.runLocked(lockName, TTL, () -> { })).isTrue();
    }

    @Test
    void locksAreScopedToTheirNameSoDistinctDatasourcesNeverContend() {
        var names = List.of(uniqueLockName(), uniqueLockName());
        var queue = new ArrayBlockingQueue<Runnable>(2);
        Executor deferred = queue::add;

        names.forEach(name ->
                assertThat(lockService.runLockedAsync(name, TTL, deferred, () -> { })).isTrue());

        assertThat(queue).hasSize(2);
        queue.forEach(Runnable::run);
    }
}
