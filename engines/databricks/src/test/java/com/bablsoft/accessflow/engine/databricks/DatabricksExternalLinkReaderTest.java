package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.engine.databricks.DatabricksResultReader.ExternalLink;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the presigned second hop against a local object-store stand-in: the absent
 * {@code Authorization} header, the URL guard, the bounded body read, and the rule that no failure
 * ever carries the URL or the response body (both of which end up persisted with the query result).
 */
class DatabricksExternalLinkReaderTest {

    private static ObjectStoreStub stub;

    @BeforeAll
    static void start() throws IOException {
        stub = new ObjectStoreStub();
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    @BeforeEach
    void reset() {
        stub.reset();
    }

    @Test
    void fetchesRowsWithoutAnAuthorizationHeader() {
        stub.bodies.put("/chunk/0", "[[\"1\",\"Ada\"],[\"2\",null]]");
        var chunk = reader(stub.origin()).read(link(0, stub.url("/chunk/0")), 1_048_576L);

        assertThat(chunk.rows()).hasSize(2);
        assertThat(chunk.rows().get(0)).containsExactly("1", "Ada");
        assertThat(chunk.rows().get(1)).containsExactly("2", null);
        assertThat(chunk.overBudget()).isFalse();
        assertThat(chunk.bytes()).isPositive();
        assertThat(stub.requests).hasSize(1);
        assertThat(stub.requests.get(0).authorization()).isNull();
    }

    @Test
    void acceptsTheDataArrayEnvelopeShapeToo() {
        stub.bodies.put("/chunk/0", "{\"data_array\":[[\"1\"]]}");
        var chunk = reader(stub.origin()).read(link(0, stub.url("/chunk/0")), 1_048_576L);

        assertThat(chunk.rows()).containsExactly(List.of("1"));
    }

    @Test
    void stopsReadingOnceTheByteBudgetIsExceeded() {
        stub.bodies.put("/chunk/0", "[[\"" + "x".repeat(4096) + "\"]]");
        var chunk = reader(stub.origin()).read(link(0, stub.url("/chunk/0")), 64L);

        assertThat(chunk.overBudget()).isTrue();
        assertThat(chunk.rows()).isEmpty();
        assertThat(chunk.bytes()).isEqualTo(65L);
    }

    @Test
    void rejectsALinkThatIsNotHttpsAndNotTheWorkspaceOrigin() {
        assertThatThrownBy(() -> reader("https://workspace.example.com")
                .read(link(4, "http://evil.internal/steal"), 1_048_576L))
                .isInstanceOf(DatabricksApiException.class)
                .satisfies(e -> {
                    assertThat(e).hasMessageContaining("chunk 4");
                    assertThat(e.getMessage()).doesNotContain("evil.internal");
                });
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void rejectsRelativeAndUserInfoBearingLinks() {
        var reader = reader("https://workspace.example.com");
        assertThatThrownBy(() -> reader.read(link(0, "/result/chunks/0"), 1L))
                .isInstanceOf(DatabricksApiException.class);
        assertThatThrownBy(() -> reader.read(link(0, "https://user:pw@store.example/0"), 1L))
                .isInstanceOf(DatabricksApiException.class);
        assertThatThrownBy(() -> reader.read(link(0, "https:// not a uri"), 1L))
                .isInstanceOf(DatabricksApiException.class);
    }

    @Test
    void acceptsAnHttpsLinkOnAnyHost() {
        assertThat(DatabricksExternalLinkReader.validate("https://bucket.s3.example/part-0",
                new DatabricksEndpoint("https://workspace.example.com", "wh"), 0))
                .hasToString("https://bucket.s3.example/part-0");
    }

    @Test
    void surfacesANonSuccessStatusWithoutTheUrlOrTheBody() {
        stub.statuses.put("/chunk/7", 403);
        stub.bodies.put("/chunk/7", "{\"secretDiagnostic\":\"presigned signature expired\"}");
        var url = stub.url("/chunk/7");

        assertThatThrownBy(() -> reader(stub.origin()).read(link(7, url), 1_048_576L))
                .isInstanceOf(DatabricksApiException.class)
                .satisfies(e -> {
                    assertThat(e).hasMessage("Failed to fetch Databricks result chunk 7 (HTTP 403)");
                    assertThat(e.getMessage()).doesNotContain(url).doesNotContain("secret");
                    assertThat(e.getCause()).isNull();
                    assertThat(((DatabricksApiException) e).statusCode()).isEqualTo(403);
                });
    }

    @Test
    void surfacesUnparseableBodiesWithoutTheUrl() {
        stub.bodies.put("/chunk/2", "not json at all");
        var url = stub.url("/chunk/2");

        assertThatThrownBy(() -> reader(stub.origin()).read(link(2, url), 1_048_576L))
                .isInstanceOf(DatabricksApiException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("chunk 2").doesNotContain(url));
    }

    @Test
    void aTransportFailureCarriesNeitherUrlNorCause() {
        // Port 1 on loopback is reliably closed; the connection refusal must stay scrubbed.
        var url = "http://127.0.0.1:1/chunk/0";
        assertThatThrownBy(() -> reader("http://127.0.0.1:1").read(link(0, url), 1_048_576L))
                .isInstanceOf(DatabricksApiException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("chunk 0").doesNotContain("127.0.0.1:1");
                    assertThat(e.getCause()).isNull();
                });
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static DatabricksExternalLinkReader reader(String baseUrl) {
        var clock = Clock.systemUTC();
        return new DatabricksExternalLinkReader(java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
                new DatabricksEndpoint(baseUrl, "wh"), clock,
                clock.instant().plus(Duration.ofSeconds(30)));
    }

    private static ExternalLink link(int chunkIndex, String url) {
        return new ExternalLink(chunkIndex, 1, 10, url);
    }

    /** Minimal stand-in for the presigned object store the external links point at. */
    private static final class ObjectStoreStub {

        record Recorded(String path, String authorization) {
        }

        final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        final Map<String, Integer> statuses = new ConcurrentHashMap<>();

        private final HttpServer server;

        ObjectStoreStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String url(String path) {
            return origin() + path;
        }

        void reset() {
            requests.clear();
            bodies.clear();
            statuses.clear();
        }

        void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            var path = exchange.getRequestURI().getPath();
            requests.add(new Recorded(path,
                    exchange.getRequestHeaders().getFirst("Authorization")));
            var body = bodies.getOrDefault(path, "[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        }
    }
}
