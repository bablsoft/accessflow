package com.bablsoft.accessflow;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AF-454: default observability wiring. Prometheus scrape endpoint is reachable unauthenticated,
 * the micrometer {@link Tracer} is present (so MDC trace ids stay populated), and no OTLP
 * {@link SpanExporter} is created when no endpoint is configured (export off by default).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
@ImportTestcontainers(TestcontainersConfig.class)
class ObservabilityActuatorIntegrationTest {

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired Tracer tracer;

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
    void prometheusEndpointIsExposedUnauthenticated() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/actuator/prometheus"))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("jvm_memory_used_bytes");
        }
    }

    @Test
    void tracerBeanIsPresentSoTraceContextStaysActive() {
        assertThat(tracer).isNotNull();
    }

    @Test
    void noOtlpSpanExporterWhenNoEndpointConfigured() {
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
    }
}
