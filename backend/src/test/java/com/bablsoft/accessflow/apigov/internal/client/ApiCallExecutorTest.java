package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCallExecutorTest {

    private final ApiCallExecutor executor = new ApiCallExecutor();

    @Test
    void grpcExecutionIsRejected() {
        assertThatThrownBy(() -> executor.execute(ApiProtocol.GRPC, "grpc://h", "X", "/p", Map.of(),
                null, 1000, 1024, "op"))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessageContaining("gRPC");
    }

    @Test
    void restGetReturnsBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var result = executor.execute(ApiProtocol.REST, base, "GET", "/data", Map.of(), null,
                    2000, 1_000_000, null);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("\"ok\":true");
            assertThat(result.truncated()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseIsCappedAtMaxBytes() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/big", exchange -> {
            byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var result = executor.execute(ApiProtocol.REST, base, "GET", "/big", Map.of(), null,
                    2000, 4, null);
            assertThat(result.truncated()).isTrue();
            assertThat(result.body()).hasSize(4);
            assertThat(result.bytes()).isEqualTo(10);
        } finally {
            server.stop(0);
        }
    }
}
