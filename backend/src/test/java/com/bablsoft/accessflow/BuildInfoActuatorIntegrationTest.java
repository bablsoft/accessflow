package com.bablsoft.accessflow;

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
class BuildInfoActuatorIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;

    @Test
    void actuatorInfoReportsBuildVersionFromSpringBootMavenBuildInfoGoal() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/info"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            var json = objectMapper.readTree(response.body());
            assertThat(json.has("build")).as("build-info goal must populate info.build.*").isTrue();
            var build = json.get("build");
            assertThat(build.get("version").asString()).isNotBlank();
            assertThat(build.get("artifact").asString()).isEqualTo("accessflow");
        }
    }
}
