package com.bablsoft.accessflow;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges the standard OpenTelemetry SDK environment variables onto the Spring Boot 4.1 OTLP
 * tracing properties so operators can wire trace export with the names every OTel user already
 * knows (AF-454):
 *
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} (or signal-specific
 *       {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}) &rarr;
 *       {@code management.opentelemetry.tracing.export.otlp.endpoint}</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_HEADERS} ({@code k1=v1,k2=v2}) &rarr;
 *       {@code management.opentelemetry.tracing.export.otlp.headers.*}</li>
 * </ul>
 *
 * <p>The Spring Boot OTLP exporter is gated by the <em>presence</em> of the endpoint property
 * ({@code @ConditionalOnProperty}), so we add it only when a non-blank endpoint is supplied —
 * keeping trace export OFF by default and avoiding an invalid empty-endpoint exporter. The value
 * is posted verbatim, so it must be the full OTLP/HTTP traces URL (e.g.
 * {@code http://tempo:4318/v1/traces}). MDC {@code traceId}/{@code spanId} are populated regardless
 * of whether export is enabled.
 */
public class OtlpTracingEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "accessflow-otlp-tracing";
    static final String ENDPOINT_PROPERTY = "management.opentelemetry.tracing.export.otlp.endpoint";
    static final String HEADERS_PROPERTY_PREFIX = "management.opentelemetry.tracing.export.otlp.headers.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var endpoint = firstNonBlank(
                environment.getProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
                environment.getProperty("OTEL_EXPORTER_OTLP_ENDPOINT"));
        if (endpoint == null) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ENDPOINT_PROPERTY, endpoint.strip());
        parseHeaders(environment.getProperty("OTEL_EXPORTER_OTLP_HEADERS"), properties);
        // addLast: explicit Spring properties (management.opentelemetry.tracing.*) still win, so
        // operators can override the bridged value if they prefer the native names.
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }

    private static void parseHeaders(String raw, Map<String, Object> target) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            var name = pair.substring(0, eq).strip();
            var value = pair.substring(eq + 1).strip();
            if (!name.isEmpty()) {
                target.put(HEADERS_PROPERTY_PREFIX + name, value);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
