package com.bablsoft.accessflow;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AF-454: when an OTLP traces endpoint is configured (here directly via the Spring property the
 * {@link OtlpTracingEnvironmentPostProcessor} bridges {@code OTEL_EXPORTER_OTLP_ENDPOINT} onto),
 * Spring Boot's auto-configuration wires an OTLP {@link SpanExporter} for the proxy-pipeline spans.
 */
@SpringBootTest(properties = {
        "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces"})
@ImportTestcontainers(TestcontainersConfig.class)
class OtlpTracingExportWiringIntegrationTest {

    @Autowired ApplicationContext context;

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
    void otlpSpanExporterIsWiredWhenEndpointConfigured() {
        assertThat(context.getBeansOfType(SpanExporter.class)).isNotEmpty();
    }
}
