package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineSampleRequestTest {

    private final SampleTableRequest request =
            new SampleTableRequest(UUID.randomUUID(), "public", "users", 50, null);
    private final DatasourceConnectionDescriptor descriptor = new DatasourceConnectionDescriptor(
            UUID.randomUUID(), UUID.randomUUID(), DbType.POSTGRESQL, "localhost", 5432, "db",
            "user", "enc", SslMode.DISABLE, 5, 1000, false, null, false, null, null, null,
            null, null, null, true, null, null);

    @Test
    void allFieldsSetIsAccepted() {
        var engineRequest = new QueryEngineSampleRequest(request, descriptor, 50,
                Duration.ofSeconds(30));

        assertThat(engineRequest.request()).isSameAs(request);
        assertThat(engineRequest.descriptor()).isSameAs(descriptor);
        assertThat(engineRequest.effectiveMaxRows()).isEqualTo(50);
        assertThat(engineRequest.effectiveTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> new QueryEngineSampleRequest(null, descriptor, 50,
                Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request");
    }

    @Test
    void nullDescriptorIsRejected() {
        assertThatThrownBy(() -> new QueryEngineSampleRequest(request, null, 50,
                Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("descriptor");
    }

    @Test
    void nullTimeoutIsRejected() {
        assertThatThrownBy(() -> new QueryEngineSampleRequest(request, descriptor, 50, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effectiveTimeout");
    }

    @Test
    void nonPositiveMaxRowsIsRejected() {
        assertThatThrownBy(() -> new QueryEngineSampleRequest(request, descriptor, 0,
                Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveMaxRows must be positive");
    }
}
