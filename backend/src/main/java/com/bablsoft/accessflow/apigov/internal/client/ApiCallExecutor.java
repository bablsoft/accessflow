package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Executes a governed call against the upstream target. REST / SOAP / GraphQL are all HTTP and run
 * through the JDK {@link HttpClient}; gRPC execution is not yet supported (connectors, schema, and
 * review still work — execution returns a clear error). The response body is read into memory and
 * capped at {@code maxResponseBytes} (the surplus is dropped and {@code truncated} is set).
 */
@Component
public class ApiCallExecutor {

    private static final Set<String> BODYLESS = Set.of("GET", "HEAD", "OPTIONS", "DELETE");

    public ApiCallResult execute(ApiProtocol protocol, String baseUrl, String verb, String path,
                                 Map<String, String> headers, String body, int timeoutMs,
                                 long maxResponseBytes, String operationId) {
        if (protocol == ApiProtocol.GRPC) {
            throw new ApiExecutionException("gRPC call execution is not yet supported");
        }
        var url = buildUrl(baseUrl, path);
        var method = httpMethod(protocol, verb);
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs));
        headers.forEach((k, v) -> {
            if (k != null && v != null && !k.isBlank()) {
                builder.header(k, v);
            }
        });
        if (protocol == ApiProtocol.GRAPHQL && !headers.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json");
        } else if (protocol == ApiProtocol.SOAP) {
            if (!headers.containsKey("Content-Type")) {
                builder.header("Content-Type", "text/xml; charset=utf-8");
            }
            if (operationId != null && !headers.containsKey("SOAPAction")) {
                builder.header("SOAPAction", "\"" + operationId + "\"");
            }
        }
        var publisher = (body == null || BODYLESS.contains(method))
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);

        long start = System.nanoTime();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()) {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000);
            byte[] raw = response.body() != null ? response.body() : new byte[0];
            boolean truncated = raw.length > maxResponseBytes;
            int keep = truncated ? (int) Math.min(maxResponseBytes, Integer.MAX_VALUE) : raw.length;
            var text = new String(raw, 0, keep, StandardCharsets.UTF_8);
            return new ApiCallResult(response.statusCode(), durationMs, raw.length, truncated, text);
        } catch (java.io.IOException ex) {
            throw new ApiExecutionException("API call failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiExecutionException("API call interrupted");
        }
    }

    private static String httpMethod(ApiProtocol protocol, String verb) {
        if (protocol == ApiProtocol.GRAPHQL || protocol == ApiProtocol.SOAP) {
            return "POST";
        }
        return verb == null || verb.isBlank() ? "GET" : verb.toUpperCase();
    }

    private static String buildUrl(String baseUrl, String path) {
        if (path == null || path.isBlank()) {
            return baseUrl;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return path.startsWith("/") ? base + path : base + "/" + path;
    }
}
