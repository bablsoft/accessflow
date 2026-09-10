package com.bablsoft.accessflow.scheduling.internal;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDistributedLockServiceTest {

    @Mock LockProvider lockProvider;
    @Mock SimpleLock simpleLock;
    @Mock Runnable action;

    @Test
    void runsActionAndUnlocksWhenLockAcquired() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));

        boolean executed = new DefaultDistributedLockService(lockProvider)
                .runLocked("bootstrapReconcile", Duration.ofMinutes(10), action);

        assertThat(executed).isTrue();
        InOrder order = inOrder(action, simpleLock);
        order.verify(action).run();
        order.verify(simpleLock).unlock();
    }

    @Test
    void skipsActionAndReturnsFalseWhenLockNotAcquired() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());

        boolean executed = new DefaultDistributedLockService(lockProvider)
                .runLocked("bootstrapReconcile", Duration.ofMinutes(10), action);

        assertThat(executed).isFalse();
        verifyNoInteractions(action);
        verify(simpleLock, never()).unlock();
    }

    @Test
    void unlocksEvenWhenActionThrows() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        var boom = new IllegalStateException("boom");
        doThrowOnRun(boom);

        assertThatThrownBy(() -> new DefaultDistributedLockService(lockProvider)
                .runLocked("bootstrapReconcile", Duration.ofMinutes(10), action))
                .isSameAs(boom);

        verify(action).run();
        verify(simpleLock, times(1)).unlock();
    }

    @Test
    void propagatesProviderExceptionWithoutUnlocking() {
        var redisDown = new IllegalStateException("redis unreachable");
        when(lockProvider.lock(any(LockConfiguration.class))).thenThrow(redisDown);

        assertThatThrownBy(() -> new DefaultDistributedLockService(lockProvider)
                .runLocked("bootstrapReconcile", Duration.ofMinutes(10), action))
                .isSameAs(redisDown);

        verifyNoInteractions(action, simpleLock);
    }

    @Test
    void passesNameAndDurationsToLockConfiguration() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        ArgumentCaptor<LockConfiguration> captor = ArgumentCaptor.forClass(LockConfiguration.class);

        new DefaultDistributedLockService(lockProvider)
                .runLocked("bootstrapReconcile", Duration.ofMinutes(7), action);

        verify(lockProvider).lock(captor.capture());
        LockConfiguration config = captor.getValue();
        assertThat(config.getName()).isEqualTo("bootstrapReconcile");
        assertThat(config.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(7));
        assertThat(config.getLockAtLeastFor()).isEqualTo(Duration.ZERO);
    }

    @Test
    void runLockedAsyncHoldsTheLockAcrossTheHandoffToTheExecutor() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        var queue = new ArrayList<Runnable>();

        boolean started = new DefaultDistributedLockService(lockProvider)
                .runLockedAsync("discoveryScan:1", Duration.ofMinutes(30), queue::add, action);

        // The whole point of the async variant: the caller already has its answer while the
        // action is still queued, and the lock is not released until that action finishes.
        assertThat(started).isTrue();
        assertThat(queue).hasSize(1);
        verifyNoInteractions(action);
        verify(simpleLock, never()).unlock();

        queue.getFirst().run();

        InOrder order = inOrder(action, simpleLock);
        order.verify(action).run();
        order.verify(simpleLock).unlock();
    }

    @Test
    void runLockedAsyncSkipsTheExecutorEntirelyWhenLockNotAcquired() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());
        List<Runnable> queue = new ArrayList<>();

        boolean started = new DefaultDistributedLockService(lockProvider)
                .runLockedAsync("discoveryScan:1", Duration.ofMinutes(30), queue::add, action);

        assertThat(started).isFalse();
        assertThat(queue).isEmpty();
        verifyNoInteractions(action);
        verify(simpleLock, never()).unlock();
    }

    @Test
    void runLockedAsyncUnlocksAndRethrowsWhenTheExecutorRejectsTheAction() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        var rejected = new RejectedExecutionException("executor is shut down");
        Executor rejecting = task -> {
            throw rejected;
        };

        assertThatThrownBy(() -> new DefaultDistributedLockService(lockProvider)
                .runLockedAsync("discoveryScan:1", Duration.ofMinutes(30), rejecting, action))
                .isSameAs(rejected);

        verifyNoInteractions(action);
        verify(simpleLock, times(1)).unlock();
    }

    @Test
    void runLockedAsyncSwallowsTheActionsExceptionAndStillUnlocksExactlyOnce() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        doThrowOnRun(new IllegalStateException("boom"));
        Executor inline = Runnable::run;

        boolean started = new DefaultDistributedLockService(lockProvider)
                .runLockedAsync("discoveryScan:1", Duration.ofMinutes(30), inline, action);

        // Nothing downstream can observe the failure — the caller returned when the lock was
        // taken — so it must be logged, not thrown, and the lock must still come back.
        assertThat(started).isTrue();
        verify(action).run();
        verify(simpleLock, times(1)).unlock();
    }

    @Test
    void runLockedAsyncPassesNameAndDurationsToLockConfiguration() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        ArgumentCaptor<LockConfiguration> captor = ArgumentCaptor.forClass(LockConfiguration.class);

        new DefaultDistributedLockService(lockProvider)
                .runLockedAsync("discoveryScan:1", Duration.ofMinutes(30), Runnable::run, action);

        verify(lockProvider).lock(captor.capture());
        LockConfiguration config = captor.getValue();
        assertThat(config.getName()).isEqualTo("discoveryScan:1");
        assertThat(config.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.getLockAtLeastFor()).isEqualTo(Duration.ZERO);
    }

    @Test
    void runLockedAsyncPropagatesProviderExceptionWithoutUnlocking() {
        var redisDown = new IllegalStateException("redis unreachable");
        when(lockProvider.lock(any(LockConfiguration.class))).thenThrow(redisDown);

        assertThatThrownBy(() -> new DefaultDistributedLockService(lockProvider)
                .runLockedAsync("discoveryScan:1", Duration.ofMinutes(30), Runnable::run, action))
                .isSameAs(redisDown);

        verifyNoInteractions(action, simpleLock);
    }

    private void doThrowOnRun(RuntimeException ex) {
        org.mockito.Mockito.doThrow(ex).when(action).run();
    }
}
