package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpCorpusIndexDispatcherTest {

    @Mock HelpCorpusIndexer indexer;
    @Mock DistributedLockService distributedLockService;

    /** Runs inline, so the assertions see the work the virtual-thread executor would have done. */
    private final Executor inline = Runnable::run;

    private HelpCorpusIndexDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new HelpCorpusIndexDispatcher(indexer, distributedLockService,
                new HelpAgentProperties(true, 64, Duration.ofMinutes(30)), inline);
    }

    private void lockAcquired() {
        when(distributedLockService.runLocked(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return true;
        });
    }

    @Test
    void indexesEveryEnabledOrganizationEachUnderItsOwnLock() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(indexer.enabledConfigIds()).thenReturn(List.of(first, second));
        lockAcquired();

        dispatcher.dispatchAll(false);

        verify(distributedLockService).runLocked(eq(HelpCorpusIndexDispatcher.lockName(first)),
                eq(Duration.ofMinutes(30)), any());
        verify(distributedLockService).runLocked(eq(HelpCorpusIndexDispatcher.lockName(second)),
                eq(Duration.ofMinutes(30)), any());
        verify(indexer).index(first, false);
        verify(indexer).index(second, false);
    }

    @Test
    void runsASingleRowPassUnderThatRowsLock() {
        var id = UUID.randomUUID();
        lockAcquired();

        dispatcher.dispatchOne(id, true);

        verify(distributedLockService).runLocked(eq(HelpCorpusIndexDispatcher.lockName(id)), any(),
                any());
        verify(indexer).index(id, true);
    }

    @Test
    void doesNothingWhenAnotherReplicaIsAlreadyIndexingThatRow() {
        var id = UUID.randomUUID();
        when(distributedLockService.runLocked(any(), any(), any())).thenReturn(false);

        dispatcher.dispatchOne(id, false);

        // The only pass that can lose is one for the same organization, whose winner is doing exactly
        // the same work; duplicating it would race its deletes and pay the embedding bill twice.
        verify(indexer, never()).index(any(), anyBoolean());
    }

    @Test
    void oneOrganizationsPassNeverBlocksAnother() {
        var busy = UUID.randomUUID();
        var free = UUID.randomUUID();
        when(indexer.enabledConfigIds()).thenReturn(List.of(busy, free));
        // Only the busy organization is locked - the global-lock version of this dropped `free`
        // silently, and it stayed unindexed until the next restart.
        when(distributedLockService.runLocked(eq(HelpCorpusIndexDispatcher.lockName(busy)), any(),
                any())).thenReturn(false);
        when(distributedLockService.runLocked(eq(HelpCorpusIndexDispatcher.lockName(free)), any(),
                any())).thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return true;
                });

        dispatcher.dispatchAll(false);

        verify(indexer, never()).index(eq(busy), anyBoolean());
        verify(indexer).index(free, false);
    }

    @Test
    void makesNoLockAttemptWhenNoOrganizationHasTheAgentEnabled() {
        when(indexer.enabledConfigIds()).thenReturn(List.of());

        dispatcher.dispatchAll(false);

        verify(distributedLockService, never()).runLocked(any(), any(), any());
    }

    @Test
    void carriesTheConfiguredLockDuration() {
        var id = UUID.randomUUID();
        dispatcher = new HelpCorpusIndexDispatcher(indexer, distributedLockService,
                new HelpAgentProperties(true, 64, Duration.ofHours(2)), inline);
        when(distributedLockService.runLocked(any(), any(), any())).thenReturn(false);

        dispatcher.dispatchOne(id, false);

        verify(distributedLockService).runLocked(any(), eq(Duration.ofHours(2)), any());
    }

    @Test
    void swallowsAFailureInsideThePass() {
        when(indexer.enabledConfigIds()).thenThrow(new IllegalStateException("database down"));

        // Nothing awaits this thread, so an escape would only be an executor stack trace.
        assertThatCode(() -> dispatcher.dispatchAll(false)).doesNotThrowAnyException();
    }

    @Test
    void swallowsALockProviderFailure() {
        when(distributedLockService.runLocked(any(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> dispatcher.dispatchOne(UUID.randomUUID(), false))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsARejectionFromAnExecutorThatIsShuttingDown() {
        dispatcher = new HelpCorpusIndexDispatcher(indexer, distributedLockService,
                new HelpAgentProperties(true, 64, Duration.ofMinutes(30)),
                task -> {
                    throw new RejectedExecutionException("shutting down");
                });

        assertThatCode(() -> dispatcher.dispatchOne(UUID.randomUUID(), false))
                .doesNotThrowAnyException();
        verify(indexer, never()).index(any(), anyBoolean());
    }

    @Test
    void eachOrganizationGetsItsOwnLockName() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        // One ShedLock namespace covers @SchedulerLock jobs and runLocked callers alike, hence the
        // prefix; the id is what keeps two organizations from excluding each other.
        assertThat(HelpCorpusIndexDispatcher.lockName(first))
                .startsWith("helpCorpusIndex:")
                .isNotEqualTo(HelpCorpusIndexDispatcher.lockName(second))
                .endsWith(first.toString());
    }
}
