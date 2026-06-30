package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a governed call against the upstream target. REST / SOAP / GraphQL are all HTTP and run
 * through the JDK {@link HttpClient}; gRPC execution is not yet supported (connectors, schema, and
 * review still work — execution returns a clear error). The body is composed per
 * {@link ApiBodyType} (raw / form-data multipart / x-www-form-urlencoded / binary) and query
 * parameters are appended to the URL. The response body is read into memory and capped at
 * {@code maxResponseBytes} (the surplus is dropped and {@code truncated} is set).
 */
@Component
public class ApiCallExecutor {

    private static final Set<String> BODYLESS = Set.of("GET", "HEAD", "OPTIONS", "DELETE");

    public ApiCallResult execute(ApiCallRequest request) {
        if (request.protocol() == ApiProtocol.GRPC) {
            throw new ApiExecutionException("gRPC call execution is not yet supported");
        }
        var url = buildUrl(request.baseUrl(), request.path(), request.queryParams());
        var method = httpMethod(request.protocol(), request.verb());
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(request.timeoutMs()));
        request.headers().forEach((k, v) -> {
            if (k != null && v != null && !k.isBlank()) {
                builder.header(k, v);
            }
        });

        var body = buildBody(request, method);
        applyProtocolHeaders(builder, request, body);
        if (body.contentType() != null && !request.headers().containsKey("Content-Type")) {
            builder.header("Content-Type", body.contentType());
        }
        builder.method(method, body.publisher());

        long start = System.nanoTime();
        try (var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(request.timeoutMs())).build()) {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000);
            byte[] raw = response.body() != null ? response.body() : new byte[0];
            boolean truncated = raw.length > request.maxResponseBytes();
            int keep = truncated ? (int) Math.min(request.maxResponseBytes(), Integer.MAX_VALUE) : raw.length;
            var text = new String(raw, 0, keep, StandardCharsets.UTF_8);
            var contentType = response.headers().firstValue("content-type").orElse(null);
            return new ApiCallResult(response.statusCode(), durationMs, raw.length, truncated, text, contentType);
        } catch (java.io.IOException ex) {
            throw new ApiExecutionException("API call failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiExecutionException("API call interrupted");
        }
    }

    private void applyProtocolHeaders(HttpRequest.Builder builder, ApiCallRequest request, BodyPayload body) {
        if (request.protocol() == ApiProtocol.GRAPHQL && body.contentType() == null
                && !request.headers().containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json");
        } else if (request.protocol() == ApiProtocol.SOAP) {
            if (body.contentType() == null && !request.headers().containsKey("Content-Type")) {
                builder.header("Content-Type", "text/xml; charset=utf-8");
            }
            if (request.operationId() != null && !request.headers().containsKey("SOAPAction")) {
                builder.header("SOAPAction", "\"" + request.operationId() + "\"");
            }
        }
    }

    private BodyPayload buildBody(ApiCallRequest request, String method) {
        var bodyType = request.bodyType() == null ? ApiBodyType.RAW : request.bodyType();
        if (bodyType == ApiBodyType.NONE || BODYLESS.contains(method)) {
            return new BodyPayload(HttpRequest.BodyPublishers.noBody(), null);
        }
        return switch (bodyType) {
            case FORM_URLENCODED -> formUrlEncoded(request.formFields());
            case FORM_DATA -> multipart(request.formFields());
            case BINARY -> binary(request);
            default -> raw(request);
        };
    }

    private BodyPayload raw(ApiCallRequest request) {
        if (request.body() == null) {
            return new BodyPayload(HttpRequest.BodyPublishers.noBody(), null);
        }
        return new BodyPayload(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8),
                blankToNull(request.contentType()));
    }

    private BodyPayload binary(ApiCallRequest request) {
        byte[] bytes = decodeBase64(request.body());
        var contentType = blankToNull(request.contentType());
        return new BodyPayload(HttpRequest.BodyPublishers.ofByteArray(bytes),
                contentType != null ? contentType : "application/octet-stream");
    }

    private BodyPayload formUrlEncoded(List<ApiFormField> fields) {
        var encoded = new StringBuilder();
        if (fields != null) {
            for (var field : fields) {
                if (field == null || field.key() == null || field.key().isBlank()) {
                    continue;
                }
                if (!encoded.isEmpty()) {
                    encoded.append('&');
                }
                encoded.append(URLEncoder.encode(field.key(), StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(field.value() == null ? "" : field.value(), StandardCharsets.UTF_8));
            }
        }
        return new BodyPayload(HttpRequest.BodyPublishers.ofString(encoded.toString(), StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded");
    }

    private BodyPayload multipart(List<ApiFormField> fields) {
        var boundary = "----AccessFlowBoundary" + Long.toHexString(System.identityHashCode(fields))
                + Integer.toHexString((fields == null ? 0 : fields.size()) * 31 + 17);
        var out = new ByteArrayOutputStream();
        if (fields != null) {
            for (var field : fields) {
                if (field == null || field.key() == null || field.key().isBlank()) {
                    continue;
                }
                writeAscii(out, "--" + boundary + "\r\n");
                if (field.type() == ApiFormField.ApiFormFieldType.FILE) {
                    var filename = field.filename() == null ? field.key() : field.filename();
                    writeAscii(out, "Content-Disposition: form-data; name=\"" + field.key()
                            + "\"; filename=\"" + filename + "\"\r\n");
                    writeAscii(out, "Content-Type: "
                            + (blankToNull(field.contentType()) != null ? field.contentType()
                            : "application/octet-stream") + "\r\n\r\n");
                    out.writeBytes(decodeBase64(field.value()));
                    writeAscii(out, "\r\n");
                } else {
                    writeAscii(out, "Content-Disposition: form-data; name=\"" + field.key() + "\"\r\n\r\n");
                    writeAscii(out, (field.value() == null ? "" : field.value()) + "\r\n");
                }
            }
        }
        writeAscii(out, "--" + boundary + "--\r\n");
        return new BodyPayload(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()),
                "multipart/form-data; boundary=" + boundary);
    }

    private static byte[] decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException ex) {
            throw new ApiExecutionException("Request body is not valid base64");
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String httpMethod(ApiProtocol protocol, String verb) {
        if (protocol == ApiProtocol.GRAPHQL || protocol == ApiProtocol.SOAP) {
            return "POST";
        }
        return verb == null || verb.isBlank() ? "GET" : verb.toUpperCase();
    }

    private static String buildUrl(String baseUrl, String path, Map<String, String> queryParams) {
        String url;
        if (path == null || path.isBlank()) {
            url = baseUrl;
        } else if (path.startsWith("http://") || path.startsWith("https://")) {
            url = path;
        } else {
            var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            url = path.startsWith("/") ? base + path : base + "/" + path;
        }
        return appendQuery(url, queryParams);
    }

    private static String appendQuery(String url, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }
        var parts = new ArrayList<String>();
        for (var entry : queryParams.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            parts.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
        }
        if (parts.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + String.join("&", parts);
    }

    private record BodyPayload(HttpRequest.BodyPublisher publisher, String contentType) {
    }
}
