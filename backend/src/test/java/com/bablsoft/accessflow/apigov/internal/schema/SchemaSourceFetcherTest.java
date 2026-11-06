package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaSourceFetcherTest {

    private final SchemaSourceFetcher fetcher = new SchemaSourceFetcher();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/spec";
    }

    @Test
    void fetchReturnsTheBodyAsUtf8() throws IOException {
        var url = serve(exchange -> {
            byte[] body = "openapi: 3.0.0 — ü".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThat(fetcher.fetch(url)).isEqualTo("openapi: 3.0.0 — ü");
    }

    @Test
    void fetchRejectsNonHttpScheme() {
        assertThatThrownBy(() -> fetcher.fetch("ftp://example.test/spec"))
                .isInstanceOf(ApiSchemaFetchException.class)
                .hasMessageContaining("http(s)");
    }

    @Test
    void fetchRejectsAUrlWithoutScheme() {
        assertThatThrownBy(() -> fetcher.fetch("example.test/spec"))
                .isInstanceOf(ApiSchemaFetchException.class);
    }

    @Test
    void fetchRejectsAnInvalidUri() {
        assertThatThrownBy(() -> fetcher.fetch("http://exa mple.test/spec"))
                .isInstanceOf(ApiSchemaFetchException.class)
                .hasMessage("Invalid schema URL");
    }

    @Test
    void fetchFailsOnNon2xx() throws IOException {
        var url = serve(exchange -> {
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(ApiSchemaFetchException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    void fetchFailsWhenTheBodyExceedsTheCap() throws IOException {
        var url = serve(exchange -> {
            var size = SchemaSourceFetcher.MAX_FETCH_BYTES + 1;
            exchange.sendResponseHeaders(200, size);
            var chunk = new byte[64 * 1024];
            try (var out = exchange.getResponseBody()) {
                for (long written = 0; written < size; written += chunk.length) {
                    out.write(chunk, 0, (int) Math.min(chunk.length, size - written));
                }
            }
        });

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(ApiSchemaFetchException.class)
                .hasMessageContaining("maximum allowed size");
    }

    @Test
    void fetchWrapsConnectionFailures() throws IOException {
        var url = serve(exchange -> exchange.close());
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(ApiSchemaFetchException.class)
                .hasMessageStartingWith("Could not fetch schema");
    }
}
