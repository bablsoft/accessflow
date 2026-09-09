package com.bablsoft.accessflow.engine.databricks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches one {@code EXTERNAL_LINKS} result chunk from its presigned cloud-storage URL (AF-633) —
 * the second hop the {@code INLINE} disposition does not have.
 *
 * <p>Three rules govern this class, all of them load-bearing:</p>
 * <ul>
 *   <li><strong>No {@code Authorization} header.</strong> The presigned URL carries its own
 *       signature and object stores reject a request that also presents a bearer token, so the
 *       workspace PAT never leaves the Statement Execution API's own host.</li>
 *   <li><strong>The URL is validated before it is followed.</strong> It arrives in a response
 *       body, so it is treated as untrusted input: absolute, no userinfo, and {@code https} unless
 *       it is same-origin with the workspace endpoint itself (the plain-HTTP stub/dev hook that
 *       the full-URL {@code jdbc_url_override} form already exists for). Deliberately <em>no</em>
 *       private-address or storage-host allow-listing: presigned hosts differ per cloud and a
 *       Private Link workspace legitimately resolves to RFC1918. The shared client sets
 *       {@code followRedirects(NEVER)}, which closes the redirect pivot.</li>
 *   <li><strong>The URL never escapes this class.</strong> It is not logged and never appears in
 *       an exception message — {@code QueryExecutionFailedException.detail()} is persisted with the
 *       query result and the host logs the whole cause chain, so a URL-bearing message or cause
 *       would store a live presigned link. Failures carry the chunk index and HTTP status only,
 *       with no cause attached.</li>
 * </ul>
 *
 * <p>The body is read into memory bounded by the caller's remaining byte budget rather than with
 * {@code BodyHandlers.ofString()}, which would be exactly the unbounded heap read the byte cap
 * exists to prevent.</p>
 */
class DatabricksExternalLinkReader implements DatabricksResultReader.ExternalLinkReader {

    private static final Logger log = LoggerFactory.getLogger(DatabricksExternalLinkReader.class);
    private static final Duration RESPONSE_GRACE = Duration.ofSeconds(30);
    private static final Duration MIN_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final int COPY_BUFFER = 8192;

    private final HttpClient http;
    private final DatabricksEndpoint endpoint;
    private final Clock clock;
    private final Instant deadline;
    private final ObjectMapper mapper = new ObjectMapper();

    DatabricksExternalLinkReader(HttpClient http, DatabricksEndpoint endpoint, Clock clock,
                                 Instant deadline) {
        this.http = http;
        this.endpoint = endpoint;
        this.clock = clock;
        this.deadline = deadline;
    }

    @Override
    public DatabricksResultReader.Chunk read(DatabricksResultReader.ExternalLink link,
                                             long byteBudget) {
        var uri = validate(link.url(), endpoint, link.chunkIndex());
        var request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout())
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("Databricks result chunk {} could not be fetched: {}", link.chunkIndex(),
                    e.getClass().getSimpleName());
            throw failure(link.chunkIndex(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(link.chunkIndex(), 0);
        }
        if (response.statusCode() / 100 != 2) {
            // ofInputStream() hands back an unconsumed body; closing it releases the connection.
            closeQuietly(response);
            throw failure(link.chunkIndex(), response.statusCode());
        }
        return parse(link.chunkIndex(), readBounded(link.chunkIndex(), response, byteBudget),
                byteBudget);
    }

    /**
     * Reads at most {@code byteBudget + 1} bytes — the extra byte is what proves the chunk
     * overflowed the budget rather than merely filling it.
     */
    private static Body readBounded(int chunkIndex, HttpResponse<InputStream> response,
                                    long byteBudget) {
        long cap = byteBudget + 1;
        var out = new ByteArrayOutputStream();
        var buffer = new byte[COPY_BUFFER];
        try (var body = response.body()) {
            int read;
            while (out.size() < cap && (read = body.read(buffer)) >= 0) {
                out.write(buffer, 0, (int) Math.min(read, cap - out.size()));
            }
        } catch (IOException e) {
            throw failure(chunkIndex, 0);
        }
        return new Body(out.toByteArray(), out.size() > byteBudget);
    }

    private record Body(byte[] bytes, boolean overBudget) {
    }

    /**
     * A {@code JSON_ARRAY} external chunk is a bare array of row arrays. Older/alternate shapes
     * wrap it in the inline {@code data_array} envelope, so both are accepted.
     */
    private DatabricksResultReader.Chunk parse(int chunkIndex, Body body, long byteBudget) {
        if (body.overBudget()) {
            // Truncated mid-transfer: the bytes are not parseable JSON, so report no rows.
            return new DatabricksResultReader.Chunk(List.of(), byteBudget + 1, true);
        }
        JsonNode root;
        try {
            root = mapper.readTree(body.bytes());
        } catch (IOException e) {
            log.warn("Databricks result chunk {} was not parseable JSON", chunkIndex);
            throw failure(chunkIndex, 0);
        }
        var array = root.isArray() ? root : root.path("data_array");
        var rows = new ArrayList<List<String>>(array.size());
        for (var row : array) {
            var values = new ArrayList<String>(row.size());
            for (var value : row) {
                values.add(value.isNull() ? null : value.asText());
            }
            rows.add(values);
        }
        return new DatabricksResultReader.Chunk(rows, body.bytes().length, false);
    }

    // ---- URL validation ---------------------------------------------------------------------------

    /** Package-private for the reader's own test; see the class javadoc for the rule. */
    static URI validate(String rawUrl, DatabricksEndpoint endpoint, int chunkIndex) {
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException e) {
            throw rejected(chunkIndex);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getScheme() == null) {
            throw rejected(chunkIndex);
        }
        if ("https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            return uri;
        }
        // Same-origin with the workspace endpoint: the plain-HTTP dev / stub-server hook.
        var origin = uri.getScheme() + "://" + uri.getRawAuthority();
        if (origin.equals(endpoint.baseUrl())) {
            return uri;
        }
        throw rejected(chunkIndex);
    }

    // ---- failures (never carry the URL, the response body, or a cause) ------------------------------

    private static DatabricksApiException rejected(int chunkIndex) {
        return new DatabricksApiException(
                "Databricks result chunk " + chunkIndex + " advertised an unusable result link",
                null, 0, false);
    }

    private static DatabricksApiException failure(int chunkIndex, int statusCode) {
        return new DatabricksApiException("Failed to fetch Databricks result chunk " + chunkIndex
                + (statusCode > 0 ? " (HTTP " + statusCode + ")" : ""), null, statusCode, false);
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException e) {
            log.debug("Discarding Databricks result chunk body failed: {}",
                    e.getClass().getSimpleName());
        }
    }

    private Duration requestTimeout() {
        var remaining = Duration.between(clock.instant(), deadline);
        if (remaining.compareTo(MIN_REQUEST_TIMEOUT) < 0) {
            remaining = MIN_REQUEST_TIMEOUT;
        }
        return remaining.compareTo(RESPONSE_GRACE) > 0 ? RESPONSE_GRACE : remaining;
    }
}
