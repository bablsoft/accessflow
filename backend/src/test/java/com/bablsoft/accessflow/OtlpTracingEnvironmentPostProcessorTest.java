package com.bablsoft.accessflow;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static com.bablsoft.accessflow.OtlpTracingEnvironmentPostProcessor.ENDPOINT_PROPERTY;
import static com.bablsoft.accessflow.OtlpTracingEnvironmentPostProcessor.HEADERS_PROPERTY_PREFIX;
import static com.bablsoft.accessflow.OtlpTracingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

class OtlpTracingEnvironmentPostProcessorTest {

    private final OtlpTracingEnvironmentPostProcessor processor = new OtlpTracingEnvironmentPostProcessor();

    @Test
    void doesNothingWhenNoEndpointConfigured() {
        var env = new MockEnvironment();

        processor.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(env.getProperty(ENDPOINT_PROPERTY)).isNull();
    }

    @Test
    void bridgesEndpointWhenSet() {
        var env = new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://tempo:4318/v1/traces");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(ENDPOINT_PROPERTY)).isEqualTo("http://tempo:4318/v1/traces");
    }

    @Test
    void trimsAndIgnoresBlankEndpoint() {
        var env = new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "   ");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(ENDPOINT_PROPERTY)).isNull();
    }

    @Test
    void signalSpecificTracesEndpointTakesPrecedence() {
        var env = new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://base:4318")
                .withProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://traces:4318/v1/traces");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(ENDPOINT_PROPERTY)).isEqualTo("http://traces:4318/v1/traces");
    }

    @Test
    void parsesHeadersIntoSpringProperties() {
        var env = new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://tempo:4318/v1/traces")
                .withProperty("OTEL_EXPORTER_OTLP_HEADERS", "x-honeycomb-team=abc123, x-other = v2 ,bad");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(HEADERS_PROPERTY_PREFIX + "x-honeycomb-team")).isEqualTo("abc123");
        assertThat(env.getProperty(HEADERS_PROPERTY_PREFIX + "x-other")).isEqualTo("v2");
    }
}
