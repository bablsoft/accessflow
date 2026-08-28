package com.bablsoft.accessflow;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AF-454: when an OTLP traces endpoint is configured (here directly via the Spring property the
 * {@link OtlpTracingEnvironmentPostProcessor} bridges {@code OTEL_EXPORTER_OTLP_ENDPOINT} onto),
 * Spring Boot's auto-configuration wires an OTLP {@link SpanExporter} for the proxy-pipeline spans.
 */
@SpringBootTest(properties = "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces")
@ImportTestcontainers(TestcontainersConfig.class)
class OtlpTracingExportWiringIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void otlpSpanExporterIsWiredWhenEndpointConfigured() {
        assertThat(context.getBeansOfType(SpanExporter.class)).isNotEmpty();
    }
}
