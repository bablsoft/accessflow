package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.internal.config.DeploygovProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link VerificationHttpFetcher} over {@code java.net.http.HttpClient} (#1024): redirects are never
 * followed, the body is capped at {@link #BODY_CAP_BYTES}, and the whole fetch — the policy's DNS
 * lookup, headers and body — is bounded by {@code accessflow.deploygov.verification-request-timeout}. The target policy
 * runs before every fetch. Failures are logged with the check name and reason only, never the URL,
 * whose query string may carry a PromQL expression.
 */
@Component
class JdkVerificationHttpFetcher implements VerificationHttpFetcher, AutoCloseable {

    static final int BODY_CAP_BYTES = 64 * 1024;
    static final String TRUNCATED_MARKER = "…[truncated]";

    private static final Logger log = LoggerFactory.getLogger(JdkVerificationHttpFetcher.class);

    private final VerificationTargetPolicy policy;
    private final Duration timeout;
    private final ExecutorService executor;
    private final HttpClient client;

    JdkVerificationHttpFetcher(VerificationTargetPolicy policy, DeploygovProperties properties) {
        this.policy = policy;
        this.timeout = properties.verificationRequestTimeout();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .executor(executor)
                .build();
    }

    @Override
    public FetchResult fetch(String checkName, URI uri) {
        // The deadline covers the policy's DNS lookup too, not only the HTTP exchange.
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<Optional<String>> verdict =
                CompletableFuture.supplyAsync(() -> policy.check(uri), executor);
        Optional<String> rejection;
        try {
            rejection = verdict.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            verdict.cancel(true);
            return failure(checkName, "timeout");
        } catch (ExecutionException ex) {
            return failure(checkName, describe(ex.getCause()));
        } catch (InterruptedException ex) {
            return interrupted(checkName, verdict);
        }
        if (rejection.isPresent()) {
            return failure(checkName, "target rejected: " + rejection.get());
        }
        var request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json, text/plain")
                .GET()
                .build();
        var future = client.sendAsync(request, info -> new BoundedBodySubscriber(BODY_CAP_BYTES));
        try {
            var response = future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            var body = response.body();
            var text = body.truncated() ? body.text() + TRUNCATED_MARKER : body.text();
            return new FetchResult.Response(response.statusCode(), text, body.truncated());
        } catch (TimeoutException ex) {
            future.cancel(true);
            return failure(checkName, "timeout");
        } catch (ExecutionException ex) {
            return failure(checkName, describe(ex.getCause()));
        } catch (InterruptedException ex) {
            return interrupted(checkName, future);
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static FetchResult interrupted(String checkName, Future<?> pending) {
        pending.cancel(true);
        Thread.currentThread().interrupt();
        return failure(checkName, "interrupted");
    }

    static String describe(Throwable cause) {
        return switch (cause) {
            case HttpTimeoutException ignored -> "timeout";
            case ConnectException ignored -> "connection refused";
            case IOException io -> "I/O error: " + io.getClass().getSimpleName();
            case null -> "request failed";
            default -> "request failed: " + cause.getClass().getSimpleName();
        };
    }

    private static FetchResult failure(String checkName, String reason) {
        log.debug("Verification check '{}' fetch failed: {}", checkName, reason);
        return new FetchResult.Failure(reason);
    }

    @Override
    public void close() {
        client.close();
        executor.close();
    }
}
