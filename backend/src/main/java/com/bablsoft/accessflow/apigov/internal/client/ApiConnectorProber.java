package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestResult;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Lightweight "test connection" probe. For HTTP-shaped protocols (REST / SOAP / GraphQL) it issues a
 * best-effort request to the base URL and treats any HTTP response (even 4xx/5xx) as
 * reachable — the goal is to confirm DNS + TCP + TLS, not endpoint correctness. For gRPC it opens a
 * TCP connection to host:port. No connector auth is injected here.
 */
@Component
public class ApiConnectorProber {

    private static final Logger log = LoggerFactory.getLogger(ApiConnectorProber.class);

    public ApiConnectionTestResult probe(ApiProtocol protocol, String baseUrl, int timeoutMs) {
        try {
            return switch (protocol) {
                case REST, SOAP, GRAPHQL -> probeHttp(baseUrl, timeoutMs);
                case GRPC -> probeTcp(baseUrl, timeoutMs);
            };
        } catch (RuntimeException ex) {
            log.debug("API connector probe failed for {}", baseUrl, ex);
            return new ApiConnectionTestResult(false, describe(ex));
        }
    }

    private ApiConnectionTestResult probeHttp(String baseUrl, int timeoutMs) {
        try (var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return new ApiConnectionTestResult(true, "HTTP " + response.statusCode());
        } catch (java.io.IOException ex) {
            return new ApiConnectionTestResult(false, describe(ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ApiConnectionTestResult(false, "Probe interrupted");
        }
    }

    private ApiConnectionTestResult probeTcp(String baseUrl, int timeoutMs) {
        var uri = URI.create(baseUrl);
        var host = uri.getHost();
        var port = uri.getPort() > 0 ? uri.getPort() : 443;
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return new ApiConnectionTestResult(true, "TCP connect to " + host + ":" + port + " ok");
        } catch (java.io.IOException ex) {
            return new ApiConnectionTestResult(false, describe(ex));
        }
    }

    private static String describe(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
