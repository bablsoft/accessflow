package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.proxy.api.QueryConcurrencyLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConcurrencyLimitingQueryExecutorTest {

    private final DefaultQueryExecutor delegate = mock(DefaultQueryExecutor.class);
    private final MessageSource messageSource = mock(MessageSource.class);

    private ConcurrencyLimitingQueryExecutor executor(int maxConcurrent, Duration acquireTimeout) {
        when(messageSource.getMessage(anyString(), any(), any()))
                .thenReturn("concurrency limit reached");
        var properties = new ProxyPoolProperties(null, null, null, null, null,
                new ProxyPoolProperties.Execution(null, null, null, null, null, maxConcurrent,
                        acquireTimeout));
        return new ConcurrencyLimitingQueryExecutor(delegate, properties, messageSource);
    }

    private static QueryExecutionRequest request() {
        return new QueryExecutionRequest(UUID.randomUUID(), "SELECT 1", QueryType.SELECT,
                null, null, List.of(), List.of(), List.of(), false, null, List.of());
    }

    @Test
    void executeDelegatesAndReleasesThePermit() {
        var expected = new UpdateExecutionResult(1, Duration.ZERO, Set.of());
        when(delegate.execute(any())).thenReturn(expected);
        var executor = executor(1, Duration.ofMillis(50));

        // Two sequential calls through a single permit prove release-on-success.
        assertThat(executor.execute(request())).isSameAs(expected);
        assertThat(executor.execute(request())).isSameAs(expected);
    }

    @Test
    void permitIsReleasedWhenTheDelegateThrows() {
        when(delegate.execute(any())).thenThrow(new IllegalStateException("boom"));
        var executor = executor(1, Duration.ofMillis(50));

        assertThatThrownBy(() -> executor.execute(request()))
                .isInstanceOf(IllegalStateException.class);
        // The permit must be back — a second call reaches the delegate instead of timing out.
        assertThatThrownBy(() -> executor.execute(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overflowExecutionIsRejectedAfterTheAcquireTimeout() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(delegate.execute(any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new UpdateExecutionResult(0, Duration.ZERO, Set.of());
        });
        var executor = executor(1, Duration.ofMillis(100));

        var holder = Thread.ofVirtual().start(() -> executor.execute(request()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var thrown = new AtomicReference<Throwable>();
            var overflow = Thread.ofVirtual().start(() -> {
                try {
                    executor.execute(request());
                } catch (RuntimeException ex) {
                    thrown.set(ex);
                }
            });
            overflow.join(Duration.ofSeconds(5));
            assertThat(thrown.get())
                    .isInstanceOf(QueryConcurrencyLimitExceededException.class)
                    .hasMessage("concurrency limit reached");
        } finally {
            release.countDown();
            holder.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void sampleTableIsGatedBehindTheSamePermits() {
        var sample = new SampleTableRequest(UUID.randomUUID(), "public", "users",
                List.of(), List.of(), List.of(), null, null);
        var expected = new SelectExecutionResult(List.of(), List.of(), 0, false, Duration.ZERO);
        when(delegate.sampleTable(sample)).thenReturn(expected);
        var executor = executor(1, Duration.ofMillis(50));

        assertThat(executor.sampleTable(sample)).isSameAs(expected);
        verify(delegate).sampleTable(sample);
    }

    @Test
    void dryRunPassesThroughWithoutTouchingTheSemaphore() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(delegate.execute(any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new UpdateExecutionResult(0, Duration.ZERO, Set.of());
        });
        var dryRunResult = QueryDryRunResult.unsupported("postgresql");
        when(delegate.dryRun(any())).thenReturn(dryRunResult);
        var executor = executor(1, Duration.ofSeconds(5));

        // Saturate the single permit, then prove dryRun still goes through instantly.
        var holder = Thread.ofVirtual().start(() -> executor.execute(request()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(executor.dryRun(request())).isSameAs(dryRunResult);
        } finally {
            release.countDown();
            holder.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void interruptedAcquireRejectsAndRestoresTheInterruptFlag() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(delegate.execute(any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new UpdateExecutionResult(0, Duration.ZERO, Set.of());
        });
        var executor = executor(1, Duration.ofSeconds(30));

        var holder = Thread.ofVirtual().start(() -> executor.execute(request()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var thrown = new AtomicReference<Throwable>();
            var interruptedFlag = new AtomicReference<Boolean>();
            var waiter = Thread.ofVirtual().start(() -> {
                try {
                    executor.execute(request());
                } catch (RuntimeException ex) {
                    thrown.set(ex);
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            // Give the waiter a moment to block on the semaphore, then interrupt it.
            Thread.sleep(100);
            waiter.interrupt();
            waiter.join(Duration.ofSeconds(5));
            assertThat(thrown.get()).isInstanceOf(QueryConcurrencyLimitExceededException.class);
            assertThat(interruptedFlag.get()).isTrue();
            verify(delegate, never()).sampleTable(any());
        } finally {
            release.countDown();
            holder.join(Duration.ofSeconds(5));
        }
    }
}
