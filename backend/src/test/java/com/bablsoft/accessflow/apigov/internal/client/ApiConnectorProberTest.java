package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConnectorProberTest {

    private final ApiConnectorProber prober = new ApiConnectorProber();

    @Test
    void httpProbeReturnsSuccessForReachableServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            var result = prober.probe(ApiProtocol.GRAPHQL, url, 2000);

            assertThat(result.success()).isTrue();
            assertThat(result.message()).contains("200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tcpProbeReturnsSuccessForOpenPort() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            var url = "grpc://127.0.0.1:" + server.getAddress().getPort();
            var result = prober.probe(ApiProtocol.GRPC, url, 2000);

            assertThat(result.success()).isTrue();
        } finally {
            server.stop(0);
        }
    }

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
