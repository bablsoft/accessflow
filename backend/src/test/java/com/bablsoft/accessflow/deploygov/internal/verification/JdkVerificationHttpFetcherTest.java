package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.internal.config.DeploygovProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JdkVerificationHttpFetcherTest {

    private HttpServer server;
    private JdkVerificationHttpFetcher fetcher;
    private final AtomicReference<String> acceptHeader = new AtomicReference<>();
    private final AtomicReference<Boolean> redirectTargetHit = new AtomicReference<>(false);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            respond(exchange, 200, "healthy");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectTargetHit.set(true);
            respond(exchange, 200, "followed");
        });
        server.createContext("/large", exchange -> respond(exchange, 200,
                "x".repeat(JdkVerificationHttpFetcher.BODY_CAP_BYTES + 10_000)));
        server.createContext("/exact", exchange -> respond(exchange, 200,
                "y".repeat(JdkVerificationHttpFetcher.BODY_CAP_BYTES)));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        server.start();
        fetcher = fetcher(List.of());
    }

    @AfterEach
    void tearDown() {
        fetcher.close();
        server.stop(0);
    }

    /** The test target is loopback, so the policy's resolver maps it to a public address. */
    private static JdkVerificationHttpFetcher fetcher(List<String> allowedHosts) {
        var properties = new DeploygovProperties(null, null, null, null, Duration.ofMillis(800),
                allowedHosts);
        var policy = new VerificationTargetPolicy(properties,
                host -> new InetAddress[] {InetAddress.getByName("203.0.113.10")});
        return new JdkVerificationHttpFetcher(policy, properties);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void returnsStatusAndBodyAndSendsTheAcceptHeader() {
        var result = fetcher.fetch("health", url("/ok"));

        assertThat(result).isEqualTo(new FetchResult.Response(200, "healthy", false));
        assertThat(acceptHeader.get()).isEqualTo("application/json, text/plain");
    }

    @Test
    void neverFollowsRedirects() {
        var result = fetcher.fetch("health", url("/redirect"));

        assertThat(result).isInstanceOf(FetchResult.Response.class);
        assertThat(((FetchResult.Response) result).status()).isEqualTo(302);
        assertThat(redirectTargetHit.get()).isFalse();
    }

    @Test
    void capsTheBodyAndMarksTheTruncation() {
        var result = (FetchResult.Response) fetcher.fetch("health", url("/large"));

        assertThat(result.truncated()).isTrue();
        assertThat(result.body()).endsWith(JdkVerificationHttpFetcher.TRUNCATED_MARKER);
        assertThat(result.body()).hasSize(JdkVerificationHttpFetcher.BODY_CAP_BYTES
                + JdkVerificationHttpFetcher.TRUNCATED_MARKER.length());
    }

    @Test
    void aBodyExactlyAtTheCapIsNotTruncated() {
        var result = (FetchResult.Response) fetcher.fetch("health", url("/exact"));

        assertThat(result.truncated()).isFalse();
        assertThat(result.body()).hasSize(JdkVerificationHttpFetcher.BODY_CAP_BYTES);
    }

    @Test
    void aSlowTargetTimesOut() {
        assertThat(fetcher.fetch("health", url("/slow"))).isEqualTo(new FetchResult.Failure("timeout"));
    }

    @Test
    void aClosedPortIsAConnectionFailure() throws IOException {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }

        var result = fetcher.fetch("health", URI.create("http://127.0.0.1:" + port + "/ok"));

        assertThat(result).isInstanceOf(FetchResult.Failure.class);
        assertThat(((FetchResult.Failure) result).reason()).isIn("connection refused", "timeout")
                .doesNotContain("127.0.0.1");
    }

    @Test
    void aSlowDnsLookupCountsAgainstTheTimeout() {
        var properties = new DeploygovProperties(null, null, null, null, Duration.ofMillis(300), List.of());
        var policy = new VerificationTargetPolicy(properties, host -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new InetAddress[] {InetAddress.getByName("203.0.113.10")};
        });
        try (var slowDns = new JdkVerificationHttpFetcher(policy, properties)) {
            var started = System.nanoTime();
            assertThat(slowDns.fetch("health", url("/ok"))).isEqualTo(new FetchResult.Failure("timeout"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
        }
        assertThat(acceptHeader.get()).isNull();
    }

    @Test
    void anInterruptedCallerGetsAFailedSampleAndKeepsItsInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThat(fetcher.fetch("health", url("/ok"))).isEqualTo(new FetchResult.Failure("interrupted"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void describeMapsEveryCauseToAFixedReason() {
        assertThat(JdkVerificationHttpFetcher.describe(new java.net.http.HttpTimeoutException("t"))).isEqualTo("timeout");
        assertThat(JdkVerificationHttpFetcher.describe(new java.net.ConnectException("c")))
                .isEqualTo("connection refused");
        assertThat(JdkVerificationHttpFetcher.describe(new java.io.EOFException("http://h/?query=secret")))
                .isEqualTo("I/O error: EOFException");
        assertThat(JdkVerificationHttpFetcher.describe(new IllegalStateException("x")))
                .isEqualTo("request failed: IllegalStateException");
        assertThat(JdkVerificationHttpFetcher.describe(null)).isEqualTo("request failed");
    }

    @Test
    void thePolicyRunsBeforeEveryFetch() {
        try (var pinned = fetcher(List.of("prom.internal"))) {
            assertThat(pinned.fetch("health", url("/ok")))
                    .isEqualTo(new FetchResult.Failure("target rejected: host_not_allowed"));
        }
        assertThat(acceptHeader.get()).isNull();
    }
}
