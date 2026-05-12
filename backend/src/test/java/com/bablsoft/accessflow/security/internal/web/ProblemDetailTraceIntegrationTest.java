package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
@ImportTestcontainers(TestcontainersConfig.class)
class ProblemDetailTraceIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
    }

    @Test
    void unauthenticatedProtectedRequestIncludesTraceIdOnSecurityHandlerResponse() throws Exception {
        var response = get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(401);
        var json = objectMapper.readTree(response.body());
        assertThat(json.get("error").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(json.has("traceId")).isTrue();
        assertThat(json.get("traceId").asText()).isNotBlank();
    }

    @Test
    void notFoundResponseIncludesTraceIdOnGlobalHandlerResponse() throws Exception {
        var response = get("/api/v1/this-route-does-not-exist");

        var json = objectMapper.readTree(response.body());
        assertThat(json.has("traceId")).isTrue();
        assertThat(json.get("traceId").asText()).isNotBlank();
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
