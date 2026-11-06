package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetches a schema document from an admin-supplied {@code sourceUrl}: http(s) only, a 15 s timeout
 * and a 5 MiB body cap. Used by upload, the filter preview and the scheduled refresh (#893), so the
 * bounds live in one place. Every failure surfaces as {@link ApiSchemaFetchException}.
 */
@Component
public class SchemaSourceFetcher {

    static final long MAX_FETCH_BYTES = 5L * 1024 * 1024;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    public String fetch(String sourceUrl) {
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException ex) {
            throw new ApiSchemaFetchException("Invalid schema URL");
        }
        var scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ApiSchemaFetchException("Schema URL must be http(s)");
        }
        try (var client = HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build()) {
            var request = HttpRequest.newBuilder(uri).timeout(FETCH_TIMEOUT).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new ApiSchemaFetchException("Schema URL returned HTTP " + response.statusCode());
            }
            byte[] body = response.body() != null ? response.body() : new byte[0];
            if (body.length > MAX_FETCH_BYTES) {
                throw new ApiSchemaFetchException("Fetched schema exceeds the maximum allowed size");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ApiSchemaFetchException("Could not fetch schema: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiSchemaFetchException("Schema fetch interrupted");
        }
    }
}
