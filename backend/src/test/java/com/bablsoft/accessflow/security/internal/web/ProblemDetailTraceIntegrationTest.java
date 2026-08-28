package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ImportTestcontainers(TestcontainersConfig.class)
class ProblemDetailTraceIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;

    @Test
    void unauthenticatedProtectedRequestIncludesTraceIdOnSecurityHandlerResponse() throws Exception {
        var response = get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(401);
        var json = objectMapper.readTree(response.body());
        assertThat(json.get("error").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(json.has("traceId")).isTrue();
        assertThat(json.get("traceId").asString()).isNotBlank();
    }

    @Test
    void notFoundResponseIncludesTraceIdOnGlobalHandlerResponse() throws Exception {
        var response = get("/api/v1/this-route-does-not-exist");

        var json = objectMapper.readTree(response.body());
        assertThat(json.has("traceId")).isTrue();
        assertThat(json.get("traceId").asString()).isNotBlank();
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
