package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Proves the cluster-safety claim against the real Redis-backed ShedLock provider rather than a mock
 * of it: two passes started at once produce exactly one indexing run.
 *
 * <p>Without this, N replicas booting together would each delete the same help scope and re-add it,
 * racing each other's deletes — a window in which a user's question retrieves nothing — and paying the
 * embedding bill N times. A unit test with a stubbed lock cannot show that the lock provider actually
 * excludes; only a shared Redis can.
 *
 * <p>The lock is keyed per {@code help_agent_config}, so the other half matters just as much: two
 * different organizations must <em>not</em> exclude each other. A pass takes minutes and is never
 * retried, so a single global lock meant the second admin to enable the agent lost silently and stayed
 * unindexed until the next restart.
 *
 * <p>One process stands in for N replicas here, which is exactly what the provider sees: ShedLock
 * arbitrates on a Redis key, not on a JVM.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpCorpusIndexDispatcherIntegrationTest {

    @Autowired HelpCorpusIndexDispatcher dispatcher;

    @MockitoBean HelpCorpusIndexer indexer;

    private final UUID helpConfigId = UUID.randomUUID();

    @Test
    void twoConcurrentPassesForOneOrganizationResultInExactlyOneIndexingRun() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var runs = new AtomicInteger();
        doAnswer(invocation -> {
            runs.incrementAndGet();
            started.countDown();
            // Hold the lock until the second attempt has certainly been made and refused.
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(indexer).index(eq(helpConfigId), anyBoolean());

        dispatcher.dispatchOne(helpConfigId, false);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatchOne(helpConfigId, false);
        // Give the loser time to be refused and finish, so a miscount would show up rather than race.
        Thread.sleep(500);

        assertThat(runs.get()).isEqualTo(1);
        release.countDown();
    }

    @Test
    void anotherOrganizationIsIndexedWhileTheFirstOneHoldsItsLock() throws Exception {
        var other = UUID.randomUUID();
        var firstStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var otherRan = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(indexer).index(eq(helpConfigId), anyBoolean());
        doAnswer(invocation -> {
            otherRan.countDown();
            return null;
        }).when(indexer).index(eq(other), anyBoolean());

        dispatcher.dispatchOne(helpConfigId, false);
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatchOne(other, false);

        // With one global lock this timed out: the second admin to enable the agent simply lost, and
        // their organization stayed unindexed until the next restart.
        assertThat(otherRan.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void theLockIsReleasedSoALaterPassCanRun() throws Exception {
        var runs = new AtomicInteger();
        var done = new CountDownLatch(2);
        doAnswer(invocation -> {
            runs.incrementAndGet();
            done.countDown();
            return null;
        }).when(indexer).index(eq(helpConfigId), anyBoolean());

        dispatcher.dispatchOne(helpConfigId, false);
        Thread.sleep(300);
        dispatcher.dispatchOne(helpConfigId, false);

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void aFullPassLocksEachOrganizationSeparately() throws Exception {
        var second = UUID.randomUUID();
        when(indexer.enabledConfigIds()).thenReturn(List.of(helpConfigId, second));
        var done = new CountDownLatch(2);
        doAnswer(invocation -> {
            done.countDown();
            return null;
        }).when(indexer).index(any(), anyBoolean());

        dispatcher.dispatchAll(false);

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
