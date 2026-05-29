package com.bablsoft.accessflow.notifications.internal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SlackResponseSenderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> received = new AtomicReference<>();

    private SlackResponseSender sender;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/respond", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        sender = new SlackResponseSender(RestClient.create(), JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void postsSerializedJsonToResponseUrl() {
        sender.send(baseUrl + "/respond", Map.of("replace_original", true, "text", "done"));

        assertThat(received.get()).contains("\"replace_original\":true").contains("\"text\":\"done\"");
    }

    @Test
    void ignoresNullOrBlankResponseUrl() {
        assertThatNoException().isThrownBy(() -> sender.send(null, Map.of("a", "b")));
        assertThatNoException().isThrownBy(() -> sender.send("  ", Map.of("a", "b")));
        assertThat(received.get()).isNull();
    }

    @Test
    void swallowsDeliveryFailures() {
        assertThatNoException().isThrownBy(() ->
                sender.send("http://localhost:1/nope", Map.of("a", "b")));
    }
}
