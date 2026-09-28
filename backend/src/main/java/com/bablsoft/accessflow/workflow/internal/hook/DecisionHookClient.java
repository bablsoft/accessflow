package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One signed call to a decision hook, turned into a {@link DecisionHookVerdict} (#945).
 *
 * <p>Nothing here ever means "allow" by accident. Every path that is not a well-formed, correctly
 * signed, 2xx answer carrying one of the four permitted decisions ends in
 * {@link DecisionHookOutcome#FAILED}, which the evaluator turns into human review. The whole call —
 * connect, send, headers <em>and</em> body — runs under one deadline: the HTTP client's own request
 * timeout stops at the headers, so a slow-drip body is cut off by closing its stream.
 *
 * <p>The method never throws for a remote failure; an exception escaping it would strand the query
 * in {@code PENDING_AI}.
 */
@Component
class DecisionHookClient {

    static final String EVENT_HEADER = "X-AccessFlow-Event";
    static final String DELIVERY_HEADER = "X-AccessFlow-Delivery";
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final int MAX_REASON_LENGTH = 500;
    static final int MIN_APPROVALS = 1;
    static final int MAX_APPROVALS = 10;

    private static final Logger log = LoggerFactory.getLogger(DecisionHookClient.class);

    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final DecisionHookUrlGuard urlGuard;
    private final ObjectMapper objectMapper;

    DecisionHookClient(HttpClient decisionHookHttpClient, ExecutorService decisionHookExecutor,
                       DecisionHookUrlGuard urlGuard, ObjectMapper objectMapper) {
        this.httpClient = decisionHookHttpClient;
        this.executor = decisionHookExecutor;
        this.urlGuard = urlGuard;
        this.objectMapper = objectMapper;
    }

    DecisionHookVerdict call(String endpointUrl, int timeoutMs, String secret, String event,
                             UUID requestId, byte[] body) {
        long started = System.nanoTime();
        var stream = new AtomicReference<InputStream>();
        // The DNS lookup of the address check runs inside the task too, so a black-holed resolver
        // is cut off by the same deadline as a slow endpoint.
        Future<Attempt> future = executor.submit(() ->
                attempt(endpointUrl, timeoutMs, secret, event, requestId, body, stream));
        Attempt attempt;
        try {
            attempt = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            closeQuietly(stream.get());
            return DecisionHookVerdict.failed(DecisionHookFailure.TIMEOUT, null, elapsed(started));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeQuietly(stream.get());
            return DecisionHookVerdict.failed(DecisionHookFailure.TRANSPORT_ERROR, null,
                    elapsed(started));
        } catch (ExecutionException ex) {
            var failure = ex.getCause() instanceof HttpTimeoutException
                    ? DecisionHookFailure.TIMEOUT
                    : DecisionHookFailure.TRANSPORT_ERROR;
            log.warn("Decision hook call failed: {}",
                    ex.getCause() == null ? ex.getMessage() : ex.getCause().toString());
            return DecisionHookVerdict.failed(failure, null, elapsed(started));
        }
        if (attempt.failure() != null) {
            return DecisionHookVerdict.failed(attempt.failure(), null, elapsed(started));
        }
        return interpret(attempt.exchange(), secret, requestId, elapsed(started));
    }

    private Attempt attempt(String endpointUrl, int timeoutMs, String secret, String event,
                            UUID requestId, byte[] body, AtomicReference<InputStream> stream)
            throws IOException, InterruptedException {
        var target = urlGuard.resolve(endpointUrl);
        if (target.blocked()) {
            return new Attempt(DecisionHookFailure.SSRF_BLOCKED, null);
        }
        if (target.uri() == null) {
            return new Attempt(DecisionHookFailure.TRANSPORT_ERROR, null);
        }
        var request = HttpRequest.newBuilder(target.uri())
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header(EVENT_HEADER, event)
                .header(DELIVERY_HEADER, requestId.toString())
                .header(DecisionHookSigner.HEADER, DecisionHookSigner.sign(body, secret))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return new Attempt(null, exchange(request, stream));
    }

    private Exchange exchange(HttpRequest request, AtomicReference<InputStream> stream)
            throws IOException, InterruptedException {
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var in = response.body()) {
            stream.set(in);
            var bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            return new Exchange(response.statusCode(),
                    response.headers().firstValue(DecisionHookSigner.HEADER).orElse(null), bytes);
        }
    }

    private DecisionHookVerdict interpret(Exchange exchange, String secret, UUID requestId,
                                          long latencyMs) {
        int status = exchange.status();
        if (status < 200 || status > 299) {
            return DecisionHookVerdict.failed(DecisionHookFailure.NON_2XX, status, latencyMs);
        }
        if (exchange.body().length > MAX_RESPONSE_BYTES) {
            return DecisionHookVerdict.failed(DecisionHookFailure.UNPARSEABLE, status, latencyMs);
        }
        if (!DecisionHookSigner.verify(exchange.body(), secret, exchange.signature())) {
            return DecisionHookVerdict.failed(DecisionHookFailure.SIGNATURE_MISMATCH, status,
                    latencyMs);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(exchange.body());
        } catch (JacksonException ex) {
            return DecisionHookVerdict.failed(DecisionHookFailure.UNPARSEABLE, status, latencyMs);
        }
        if (root == null || !root.isObject()
                || !textOf(root, "request_id").equals(requestId.toString())) {
            return DecisionHookVerdict.failed(DecisionHookFailure.UNPARSEABLE, status, latencyMs);
        }
        var outcome = decisionOf(textOf(root, "decision"));
        if (outcome == null) {
            return DecisionHookVerdict.failed(DecisionHookFailure.INVALID_DECISION, status,
                    latencyMs);
        }
        Integer approvals = null;
        if (outcome == DecisionHookOutcome.ESCALATE || outcome == DecisionHookOutcome.REQUIRE_APPROVALS) {
            var node = root.get("approvals");
            if (node == null || node.isNull()) {
                if (outcome == DecisionHookOutcome.REQUIRE_APPROVALS) {
                    return DecisionHookVerdict.failed(DecisionHookFailure.INVALID_DECISION, status,
                            latencyMs);
                }
                approvals = MIN_APPROVALS;
            } else if (!node.isIntegralNumber() || !node.canConvertToInt()
                    || node.intValue() < MIN_APPROVALS || node.intValue() > MAX_APPROVALS) {
                return DecisionHookVerdict.failed(DecisionHookFailure.INVALID_DECISION, status,
                        latencyMs);
            } else {
                approvals = node.intValue();
            }
        }
        return new DecisionHookVerdict(outcome, null, approvals, reasonOf(root), status, latencyMs);
    }

    /** Only the four permitted decisions; {@code AUTO_APPROVE}, {@code APPROVE} and the rest are null. */
    private static DecisionHookOutcome decisionOf(String decision) {
        return switch (decision) {
            case "ALLOW" -> DecisionHookOutcome.ALLOW;
            case "ESCALATE" -> DecisionHookOutcome.ESCALATE;
            case "REQUIRE_APPROVALS" -> DecisionHookOutcome.REQUIRE_APPROVALS;
            case "REJECT" -> DecisionHookOutcome.REJECT;
            default -> null;
        };
    }

    private static String reasonOf(JsonNode root) {
        var node = root.get("reason");
        if (node == null || !node.isString()) {
            return null;
        }
        var reason = node.asString().strip();
        if (reason.isEmpty()) {
            return null;
        }
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }

    private static String textOf(JsonNode root, String field) {
        var node = root.get(field);
        return node != null && node.isString() ? node.asString() : "";
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ex) {
            log.debug("Closing a timed-out decision hook response failed: {}", ex.getMessage());
        }
    }

    private static long elapsed(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private record Exchange(int status, String signature, byte[] body) {
    }

    /** Either a refusal decided before any request was sent, or the exchange itself. */
    private record Attempt(DecisionHookFailure failure, Exchange exchange) {
    }
}
