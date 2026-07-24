package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.proxy.api.QueryConcurrencyLimitExceededException;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Primary;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Global concurrency budget over the query executor (issue #49): at most
 * {@code accessflow.proxy.execution.max-concurrent} row-materializing executions are in flight
 * across all datasources; excess callers block up to {@code acquire-timeout} then get a 503.
 *
 * <p>{@link #dryRun} is deliberately unguarded — it returns a plan, never rows, and is already
 * bounded per-datasource by HikariCP. Per-datasource concurrency is likewise HikariCP's job; this
 * decorator only protects the JVM heap as a whole.
 */
@Service
@Primary
class ConcurrencyLimitingQueryExecutor implements QueryExecutor {

    private final DefaultQueryExecutor delegate;
    private final MessageSource messageSource;
    private final Semaphore permits;
    private final int maxConcurrent;
    private final Duration acquireTimeout;

    ConcurrencyLimitingQueryExecutor(DefaultQueryExecutor delegate, ProxyPoolProperties properties,
                                     MessageSource messageSource) {
        this.delegate = delegate;
        this.messageSource = messageSource;
        this.maxConcurrent = properties.execution().maxConcurrent();
        this.acquireTimeout = properties.execution().acquireTimeout();
        this.permits = new Semaphore(maxConcurrent, true);
    }

    @Override
    public QueryExecutionResult execute(QueryExecutionRequest request) {
        return withPermit(() -> delegate.execute(request));
    }

    @Override
    public QueryDryRunResult dryRun(QueryExecutionRequest request) {
        return delegate.dryRun(request);
    }

    // Like dryRun: a COUNT(*) materializes a single row, so the heap-protecting permit budget
    // does not apply; per-datasource concurrency stays HikariCP's job.
    @Override
    public QueryAffectedRowsResult countAffectedRows(QueryExecutionRequest request) {
        return delegate.countAffectedRows(request);
    }

    @Override
    public SelectExecutionResult sampleTable(SampleTableRequest request) {
        return withPermit(() -> delegate.sampleTable(request));
    }

    private <T> T withPermit(Supplier<T> action) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeout.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw limitExceeded();
        }
        if (!acquired) {
            throw limitExceeded();
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    private QueryConcurrencyLimitExceededException limitExceeded() {
        return new QueryConcurrencyLimitExceededException(messageSource.getMessage(
                "error.query_concurrency_limit",
                new Object[]{maxConcurrent, acquireTimeout.toSeconds()},
                LocaleContextHolder.getLocale()));
    }
}
