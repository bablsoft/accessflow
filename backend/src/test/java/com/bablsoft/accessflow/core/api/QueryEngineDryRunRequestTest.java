package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineDryRunRequestTest {

    private final QueryExecutionRequest request = new QueryExecutionRequest(UUID.randomUUID(),
            "SELECT 1", QueryType.SELECT, null, null, null, null, null, false, null);
    private final DatasourceConnectionDescriptor descriptor = new DatasourceConnectionDescriptor(
            UUID.randomUUID(), UUID.randomUUID(), DbType.POSTGRESQL, "localhost", 5432, "db",
            "user", "enc", SslMode.DISABLE, 5, 1000, false, null, false, null, null, null,
            null, null, null, true, null, null);

    @Test
    void allFieldsSetIsAccepted() {
        var engineRequest = new QueryEngineDryRunRequest(request, descriptor, Duration.ofSeconds(30));

        assertThat(engineRequest.request()).isSameAs(request);
        assertThat(engineRequest.descriptor()).isSameAs(descriptor);
        assertThat(engineRequest.effectiveTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> new QueryEngineDryRunRequest(null, descriptor,
                Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request");
    }

    @Test
    void nullDescriptorIsRejected() {
        assertThatThrownBy(() -> new QueryEngineDryRunRequest(request, null, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("descriptor");
    }

    @Test
    void nullTimeoutIsRejected() {
        assertThatThrownBy(() -> new QueryEngineDryRunRequest(request, descriptor, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effectiveTimeout");
    }
}
