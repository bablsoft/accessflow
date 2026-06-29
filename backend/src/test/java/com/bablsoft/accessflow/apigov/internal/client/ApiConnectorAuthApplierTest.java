package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiConnectorAuthApplierTest {

    private final ApiConnectorAuthApplier applier = new ApiConnectorAuthApplier(JsonMapper.builder().build());

    @Test
    void noneYieldsNoHeaders() {
        assertThat(applier.authHeaders(ApiAuthMethod.NONE, Map.of(), 1000)).isEmpty();
        assertThat(applier.authHeaders(null, null, 1000)).isEmpty();
    }

    @Test
    void bearerTokenSetsAuthorization() {
        var headers = applier.authHeaders(ApiAuthMethod.BEARER_TOKEN, Map.of("token", "abc"), 1000);
        assertThat(headers).containsEntry("Authorization", "Bearer abc");
    }

    @Test
    void basicEncodesCredentials() {
        var headers = applier.authHeaders(ApiAuthMethod.BASIC, Map.of("username", "u", "password", "p"), 1000);
        assertThat(headers.get("Authorization")).startsWith("Basic ");
    }

    @Test
    void apiKeyUsesConfiguredHeaderName() {
        var headers = applier.authHeaders(ApiAuthMethod.API_KEY,
                Map.of("header-name", "X-Key", "api-key", "secret"), 1000);
        assertThat(headers).containsEntry("X-Key", "secret");
    }

    @Test
    void customHeaderSetsConfiguredHeader() {
        var headers = applier.authHeaders(ApiAuthMethod.CUSTOM_HEADER,
                Map.of("header-name", "X-Auth", "header-value", "v"), 1000);
        assertThat(headers).containsEntry("X-Auth", "v");
    }

    @Test
    void mtlsYieldsNoHeaders() {
        assertThat(applier.authHeaders(ApiAuthMethod.MTLS, Map.of(), 1000)).isEmpty();
    }

    @Test
    void oauth2ClientCredentialsFetchesAndSetsBearer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"tok-123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
            var headers = applier.authHeaders(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS,
                    Map.of("token-url", tokenUrl, "client-id", "id", "client-secret", "sec",
                            "scopes", "read"), 2000);
            assertThat(headers).containsEntry("Authorization", "Bearer tok-123");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oauth2WithoutTokenUrlFails() {
        assertThatThrownBy(() -> applier.authHeaders(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS,
                Map.of("client-id", "id"), 1000))
                .isInstanceOf(ApiExecutionException.class);
    }
}
