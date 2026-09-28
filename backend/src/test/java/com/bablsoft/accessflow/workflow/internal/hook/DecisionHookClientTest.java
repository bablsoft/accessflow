package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.internal.config.DecisionHookProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client against a real local HTTP server. The fail-closed matrix lives here: every way an
 * endpoint can misbehave must come back as {@link DecisionHookOutcome#FAILED}, never as an answer.
 */
class DecisionHookClientTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private HttpServer server;
    private ExecutorService executor;
    private DecisionHookClient client;
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    private final UUID requestId = UUID.randomUUID();
    private final byte[] body = "{\"request_id\":\"x\"}".getBytes(StandardCharsets.UTF_8);

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Captured(String event, String delivery, String signature, byte[] body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var headers = exchange.getRequestHeaders();
            captured.set(new Captured(headers.getFirst(DecisionHookClient.EVENT_HEADER),
                    headers.getFirst(DecisionHookClient.DELIVERY_HEADER),
                    headers.getFirst(DecisionHookSigner.HEADER),
                    exchange.getRequestBody().readAllBytes()));
            handler.get().handle(exchange);
        });
        server.start();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        var guard = new DecisionHookUrlGuard(new DecisionHookProperties(true, null, null));
        client = new DecisionHookClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), executor, guard,
                JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.close();
    }

    @Test
    void aSignedAllowIsAnAnswerAndTheRequestIsSigned() {
        respondSigned(200, decision("ALLOW", null, "fine"));

        var verdict = call(2000);

        assertThat(verdict.outcome()).isEqualTo(DecisionHookOutcome.ALLOW);
        assertThat(verdict.failure()).isNull();
        assertThat(verdict.reason()).isEqualTo("fine");
        assertThat(verdict.httpStatus()).isEqualTo(200);
        var request = captured.get();
        assertThat(request.event()).isEqualTo("QUERY_DECISION");
        assertThat(request.delivery()).isEqualTo(requestId.toString());
        assertThat(DecisionHookSigner.verify(request.body(), SECRET, request.signature())).isTrue();
    }

    @Test
    void escalateDefaultsToOneApproval() {
        respondSigned(200, decision("ESCALATE", null, null));

        var verdict = call(2000);

        assertThat(verdict.outcome()).isEqualTo(DecisionHookOutcome.ESCALATE);
        assertThat(verdict.requestedApprovals()).isEqualTo(1);
        assertThat(verdict.reason()).isNull();
    }

    @Test
    void requireApprovalsCarriesTheCount() {
        respondSigned(200, decision("REQUIRE_APPROVALS", "4", null));

        var verdict = call(2000);

        assertThat(verdict.outcome()).isEqualTo(DecisionHookOutcome.REQUIRE_APPROVALS);
        assertThat(verdict.requestedApprovals()).isEqualTo(4);
    }

    @Test
    void rejectIsAnAnswer() {
        respondSigned(200, decision("REJECT", null, "no"));

        assertThat(call(2000).outcome()).isEqualTo(DecisionHookOutcome.REJECT);
    }

    @Test
    void aLongReasonIsTruncated() {
        respondSigned(200, decision("REJECT", null, "x".repeat(900)));

        assertThat(call(2000).reason()).hasSize(DecisionHookClient.MAX_REASON_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO_APPROVE", "APPROVE", "allow", "", "PERMIT"})
    void anyOtherDecisionFailsClosed(String decision) {
        respondSigned(200, decision(decision, null, null));

        assertFailure(call(2000), DecisionHookFailure.INVALID_DECISION);
    }

    @Test
    void aMissingDecisionFailsClosed() {
        respondSigned(200, "{\"request_id\":\"" + requestId + "\"}");

        assertFailure(call(2000), DecisionHookFailure.INVALID_DECISION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "11", "-1", "1.5", "\"2\""})
    void anOutOfRangeApprovalCountFailsClosed(String approvals) {
        respondSigned(200, decision("ESCALATE", approvals, null));

        assertFailure(call(2000), DecisionHookFailure.INVALID_DECISION);
    }

    @Test
    void requireApprovalsWithoutACountFailsClosed() {
        respondSigned(200, decision("REQUIRE_APPROVALS", null, null));

        assertFailure(call(2000), DecisionHookFailure.INVALID_DECISION);
    }

    @Test
    void aMissingSignatureFailsClosed() {
        handler.set(exchange -> send(exchange, 200, decision("ALLOW", null, null), null));

        assertFailure(call(2000), DecisionHookFailure.SIGNATURE_MISMATCH);
    }

    @Test
    void aWrongSignatureFailsClosed() {
        var payload = decision("ALLOW", null, null);
        handler.set(exchange -> send(exchange, 200, payload,
                DecisionHookSigner.sign(payload.getBytes(StandardCharsets.UTF_8), "wrong-secret")));

        assertFailure(call(2000), DecisionHookFailure.SIGNATURE_MISMATCH);
    }

    @Test
    void aSignatureOverADifferentBodyFailsClosed() {
        handler.set(exchange -> send(exchange, 200, decision("REJECT", null, null),
                DecisionHookSigner.sign(decision("ALLOW", null, null)
                        .getBytes(StandardCharsets.UTF_8), SECRET)));

        assertFailure(call(2000), DecisionHookFailure.SIGNATURE_MISMATCH);
    }

    @Test
    void aMismatchedRequestIdFailsClosed() {
        respondSigned(200, "{\"request_id\":\"" + UUID.randomUUID() + "\",\"decision\":\"ALLOW\"}");

        assertFailure(call(2000), DecisionHookFailure.UNPARSEABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "\"ALLOW\"", "{\"decision\":\"ALLOW\"}"})
    void anUnparseableBodyFailsClosed(String payload) {
        respondSigned(200, payload);

        assertFailure(call(2000), DecisionHookFailure.UNPARSEABLE);
    }

    @Test
    void anOversizedBodyFailsClosed() {
        respondSigned(200, " ".repeat(DecisionHookClient.MAX_RESPONSE_BYTES + 10));

        assertFailure(call(2000), DecisionHookFailure.UNPARSEABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 403, 500, 503})
    void aNon2xxStatusFailsClosedEvenWithAValidBody(int status) {
        handler.set(exchange -> {
            exchange.getResponseHeaders().add("Location", "https://8.8.8.8/elsewhere");
            var payload = decision("ALLOW", null, null);
            send(exchange, status, payload,
                    DecisionHookSigner.sign(payload.getBytes(StandardCharsets.UTF_8), SECRET));
        });

        var verdict = call(2000);

        assertFailure(verdict, DecisionHookFailure.NON_2XX);
        assertThat(verdict.httpStatus()).isEqualTo(status);
    }

    @Test
    void aSlowResponseTimesOut() {
        handler.set(exchange -> {
            sleep(1500);
            send(exchange, 200, decision("ALLOW", null, null), null);
        });

        var verdict = call(200);

        assertFailure(verdict, DecisionHookFailure.TIMEOUT);
        assertThat(verdict.latencyMs()).isLessThan(1500);
    }

    @Test
    void aSlowDripBodyIsCutOffByTheOverallDeadline() {
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 20; i++) {
                    out.write(' ');
                    out.flush();
                    sleep(100);
                }
            } catch (IOException ex) {
                // The client closed the stream: exactly what the test expects.
            }
        });

        assertFailure(call(300), DecisionHookFailure.TIMEOUT);
    }

    @Test
    void aRefusedConnectionIsATransportError() throws IOException {
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        var verdict = client.call("http://127.0.0.1:" + port + "/", 2000, SECRET,
                "QUERY_DECISION", requestId, body);

        assertFailure(verdict, DecisionHookFailure.TRANSPORT_ERROR);
    }

    @Test
    void anUnresolvableHostIsATransportError() {
        var verdict = client.call("http://no-such-host.invalid/", 2000, SECRET, "QUERY_DECISION",
                requestId, body);

        assertFailure(verdict, DecisionHookFailure.TRANSPORT_ERROR);
    }

    @Test
    void aRestrictedAddressIsRefusedWithoutACall() {
        var strict = new DecisionHookClient(HttpClient.newHttpClient(), executor,
                new DecisionHookUrlGuard(new DecisionHookProperties(false, null, null)),
                JsonMapper.builder().build());
        handler.set(exchange -> send(exchange, 200, "{}", null));

        var verdict = strict.call("https://127.0.0.1:" + server.getAddress().getPort() + "/", 2000,
                SECRET, "QUERY_DECISION", requestId, body);

        assertFailure(verdict, DecisionHookFailure.SSRF_BLOCKED);
        assertThat(captured.get()).isNull();
    }

    @Test
    void theTestVerdictMirrorsTheCallVerdict() {
        respondSigned(200, decision("ESCALATE", "2", "r"));

        var result = call(2000).toTestResult();

        assertThat(result.outcome()).isEqualTo(DecisionHookOutcome.ESCALATE);
        assertThat(result.requestedApprovals()).isEqualTo(2);
        assertThat(result.reason()).isEqualTo("r");
        assertThat(result.httpStatus()).isEqualTo(200);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private DecisionHookVerdict call(int timeoutMs) {
        return client.call("http://127.0.0.1:" + server.getAddress().getPort() + "/decide",
                timeoutMs, SECRET, "QUERY_DECISION", requestId, body);
    }

    private static void assertFailure(DecisionHookVerdict verdict, DecisionHookFailure failure) {
        assertThat(verdict.outcome()).isEqualTo(DecisionHookOutcome.FAILED);
        assertThat(verdict.failure()).isEqualTo(failure);
        assertThat(verdict.requestedApprovals()).isNull();
        assertThat(verdict.isFailure()).isTrue();
    }

    private String decision(String decision, String approvals, String reason) {
        var json = new StringBuilder("{\"request_id\":\"" + requestId + "\",\"decision\":\""
                + decision + "\"");
        if (approvals != null) {
            json.append(",\"approvals\":").append(approvals);
        }
        if (reason != null) {
            json.append(",\"reason\":\"").append(reason).append('"');
        }
        return json.append('}').toString();
    }

    private void respondSigned(int status, String payload) {
        handler.set(exchange -> send(exchange, status, payload,
                DecisionHookSigner.sign(payload.getBytes(StandardCharsets.UTF_8), SECRET)));
    }

    private static void send(HttpExchange exchange, int status, String payload, String signature)
            throws IOException {
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (signature != null) {
            exchange.getResponseHeaders().add(DecisionHookSigner.HEADER, signature);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
