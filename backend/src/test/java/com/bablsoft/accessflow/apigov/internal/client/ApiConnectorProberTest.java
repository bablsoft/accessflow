package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConnectorProberTest {

    private final ApiConnectorProber prober = new ApiConnectorProber();

    @Test
    void httpProbeReturnsFailureForRefusedPort() {
        // Port 1 on loopback reliably refuses, independent of DNS/captive-portal behaviour.
        var result = prober.probe(ApiProtocol.REST, "http://127.0.0.1:1", 200);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void tcpProbeReturnsFailureForClosedPort() {
        var result = prober.probe(ApiProtocol.GRPC, "grpc://localhost:1", 200);

        assertThat(result.success()).isFalse();
    }

    @Test
    void malformedUrlIsReportedAsFailure() {
        var result = prober.probe(ApiProtocol.REST, "::::not a url", 200);

        assertThat(result.success()).isFalse();
    }
}
