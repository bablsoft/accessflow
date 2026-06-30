package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCallExecutorTest {

    private final ApiCallExecutor executor = new ApiCallExecutor();

    private static ApiCallRequest request(ApiProtocol protocol, String baseUrl, String verb, String path,
                                          Map<String, String> headers, Map<String, String> query,
                                          ApiBodyType bodyType, String body, String contentType,
                                          List<ApiFormField> formFields, long maxBytes) {
        return new ApiCallRequest(protocol, baseUrl, verb, path, headers, query, bodyType, body,
                contentType, formFields, null, 2000, maxBytes, null);
    }

    @Test
    void grpcExecutionIsRejected() {
        var req = request(ApiProtocol.GRPC, "grpc://h", "X", "/p", Map.of(), Map.of(), ApiBodyType.NONE,
                null, null, List.of(), 1024);
        assertThatThrownBy(() -> executor.execute(req))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessageContaining("gRPC");
    }

    @Test
    void restGetReturnsBodyAndContentType() throws Exception {
        withServer("/data", exchange -> respond(exchange, 200, "{\"ok\":true}", "application/json"), (base, captured) -> {
            var result = executor.execute(request(ApiProtocol.REST, base, "GET", "/data", Map.of(), Map.of(),
                    ApiBodyType.NONE, null, null, List.of(), 1_000_000));
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("\"ok\":true");
            assertThat(result.truncated()).isFalse();
            assertThat(result.contentType()).contains("application/json");
        });
    }

    @Test
    void responseIsCappedAtMaxBytes() throws Exception {
        withServer("/big", exchange -> respond(exchange, 200, "0123456789", null), (base, captured) -> {
            var result = executor.execute(request(ApiProtocol.REST, base, "GET", "/big", Map.of(), Map.of(),
                    ApiBodyType.NONE, null, null, List.of(), 4));
            assertThat(result.truncated()).isTrue();
            assertThat(result.body()).hasSize(4);
            assertThat(result.bytes()).isEqualTo(10);
        });
    }

    @Test
    void queryParamsAreAppendedToUrl() throws Exception {
        var capturedUri = new AtomicReference<String>();
        withServer("/q", exchange -> {
            capturedUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "ok", null);
        }, (base, captured) -> {
            executor.execute(request(ApiProtocol.REST, base, "GET", "/q", Map.of(),
                    Map.of("a", "1 2"), ApiBodyType.NONE, null, null, List.of(), 1000));
        });
        assertThat(capturedUri.get()).contains("a=1+2");
    }

    @Test
    void rawBodyUsesProvidedContentType() throws Exception {
        var captured = new CapturedRequest();
        withServer("/r", exchange -> capture(exchange, captured), (base, c) -> {
            executor.execute(request(ApiProtocol.REST, base, "POST", "/r", Map.of(), Map.of(),
                    ApiBodyType.RAW, "{\"x\":1}", "application/json", List.of(), 1000));
        });
        assertThat(captured.body).isEqualTo("{\"x\":1}");
        assertThat(captured.contentType).contains("application/json");
    }

    @Test
    void formUrlencodedBodyIsEncoded() throws Exception {
        var captured = new CapturedRequest();
        withServer("/f", exchange -> capture(exchange, captured), (base, c) -> {
            executor.execute(request(ApiProtocol.REST, base, "POST", "/f", Map.of(), Map.of(),
                    ApiBodyType.FORM_URLENCODED, null, null,
                    List.of(new ApiFormField("name", ApiFormField.ApiFormFieldType.TEXT, "a b", null, null)),
                    1000));
        });
        assertThat(captured.contentType).contains("application/x-www-form-urlencoded");
        assertThat(captured.body).isEqualTo("name=a+b");
    }

    @Test
    void formDataBuildsMultipartWithFilePart() throws Exception {
        var captured = new CapturedRequest();
        var fileBase64 = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        withServer("/m", exchange -> capture(exchange, captured), (base, c) -> {
            executor.execute(request(ApiProtocol.REST, base, "POST", "/m", Map.of(), Map.of(),
                    ApiBodyType.FORM_DATA, null, null,
                    List.of(new ApiFormField("field", ApiFormField.ApiFormFieldType.TEXT, "v", null, null),
                            new ApiFormField("doc", ApiFormField.ApiFormFieldType.FILE, fileBase64, "a.txt",
                                    "text/plain")),
                    1_000_000));
        });
        assertThat(captured.contentType).contains("multipart/form-data; boundary=");
        assertThat(captured.body).contains("name=\"field\"").contains("filename=\"a.txt\"").contains("hello");
    }

    @Test
    void binaryBodyDecodesBase64() throws Exception {
        var captured = new CapturedRequest();
        var base64 = Base64.getEncoder().encodeToString("BIN".getBytes(StandardCharsets.UTF_8));
        withServer("/b", exchange -> capture(exchange, captured), (base, c) -> {
            executor.execute(request(ApiProtocol.REST, base, "PUT", "/b", Map.of(), Map.of(),
                    ApiBodyType.BINARY, base64, "application/octet-stream", List.of(), 1000));
        });
        assertThat(captured.body).isEqualTo("BIN");
        assertThat(captured.contentType).contains("application/octet-stream");
    }

    private static final class CapturedRequest {
        private String body;
        private String contentType;
    }

    private interface ServerBody {
        void handle(HttpExchange exchange) throws IOException;
    }

    private interface ServerTest {
        void run(String base, AtomicReference<String> captured) throws Exception;
    }

    private static void withServer(String path, ServerBody handler, ServerTest test) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            test.run("http://127.0.0.1:" + server.getAddress().getPort(), new AtomicReference<>());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void capture(HttpExchange exchange, CapturedRequest captured) throws IOException {
        captured.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        captured.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        respond(exchange, 200, "ok", null);
    }
}
