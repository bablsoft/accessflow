package com.bablsoft.accessflow.engine.databricks;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Materializes a {@code SUCCEEDED} statement's result, for both dispositions, into the columns +
 * rows {@code DatabricksStatementClient} hands on to the result mapper. Deliberately free of HTTP:
 * the two ways of reaching more data are injected as {@link ChunkSource} (the authorized
 * {@code GET …/result/chunks/{n}} hop, which both dispositions share) and {@link ExternalLinkReader}
 * (the presigned second hop, {@code EXTERNAL_LINKS} only), so the loop itself is unit-testable.
 *
 * <p>An {@code INLINE} chunk carries its rows in {@code data_array}; an {@code EXTERNAL_LINKS}
 * chunk instead carries {@code external_links}, each a presigned URL that has to be fetched
 * separately (AF-633). Streaming stops early at the caller's row cap — the host-computed
 * {@code maxRows + 1} sentinel, which the mapper then trims — and, for external chunks only, at the
 * engine's {@code max-result-bytes} backstop; inline results need no byte budget because the API
 * caps them at ~25 MiB itself, which is the whole reason the external path exists.</p>
 */
class DatabricksResultReader {

    /**
     * One presigned result chunk. {@code toString()} is overridden because the record's generated
     * one would print the presigned URL, which must never reach a log line or an error detail.
     */
    record ExternalLink(int chunkIndex, long rowCount, long byteCount, String url) {

        @Override
        public String toString() {
            return "ExternalLink[chunkIndex=" + chunkIndex + ", rowCount=" + rowCount
                    + ", byteCount=" + byteCount + ", url=<redacted>]";
        }
    }

    /** The outcome of fetching one presigned link: its rows, its transferred size, and whether the byte budget stopped the read short. */
    record Chunk(List<List<String>> rows, long bytes, boolean overBudget) {
    }

    /** Fetches the next chunk envelope from the authorized {@code …/result/chunks/{n}} endpoint. */
    @FunctionalInterface
    interface ChunkSource {
        JsonNode fetch(int chunkIndex);
    }

    /** Fetches one presigned link, reading at most {@code byteBudget} bytes. */
    @FunctionalInterface
    interface ExternalLinkReader {
        Chunk read(ExternalLink link, long byteBudget);
    }

    private final long maxResultBytes;

    DatabricksResultReader(long maxResultBytes) {
        this.maxResultBytes = maxResultBytes;
    }

    DatabricksStatementClient.StatementResult read(JsonNode terminalResponse, Integer rowLimit,
                                                   ChunkSource chunks, ExternalLinkReader links) {
        var manifest = terminalResponse.path("manifest");
        var columns = new ArrayList<DatabricksStatementClient.Column>();
        for (var column : manifest.path("schema").path("columns")) {
            columns.add(new DatabricksStatementClient.Column(column.path("name").asText(""),
                    column.path("type_name").asText("")));
        }

        var rows = new ArrayList<List<String>>();
        var state = new Budget();
        var node = terminalResponse.path("result");
        while (node != null && !node.isMissingNode()) {
            appendChunk(node, rows, rowLimit, links, state);
            if (state.truncation != DatabricksStatementClient.Truncation.NONE
                    || rowCapReached(rows, rowLimit, state)) {
                break;
            }
            var next = nextChunkIndex(node);
            node = next == null ? null : chunks.fetch(next);
        }

        var truncation = state.truncation;
        if (truncation == DatabricksStatementClient.Truncation.NONE
                && manifest.path("truncated").asBoolean(false)) {
            truncation = DatabricksStatementClient.Truncation.ROW_LIMIT;
        }
        return new DatabricksStatementClient.StatementResult(List.copyOf(columns), rows, truncation);
    }

    // ---- chunk handling --------------------------------------------------------------------------

    /** Mutable streaming state: bytes consumed so far and why streaming stopped, if it did. */
    private static final class Budget {
        private long bytesUsed;
        private DatabricksStatementClient.Truncation truncation =
                DatabricksStatementClient.Truncation.NONE;
    }

    private void appendChunk(JsonNode chunk, List<List<String>> rows, Integer rowLimit,
                             ExternalLinkReader links, Budget state) {
        var external = chunk.path("external_links");
        if (!external.isArray()) {
            appendRows(chunk.path("data_array"), rows, rowLimit, state);
            return;
        }
        for (var link : externalLinks(external)) {
            if (rowCapReached(rows, rowLimit, state)) {
                return;
            }
            long budget = maxResultBytes - state.bytesUsed;
            // Skip a link whose advertised size alone blows the budget rather than transferring it.
            if (budget <= 0 || link.byteCount() > budget) {
                state.truncation = DatabricksStatementClient.Truncation.BYTE_LIMIT;
                return;
            }
            var fetched = links.read(link, budget);
            state.bytesUsed += fetched.bytes();
            appendRows(fetched.rows(), rows, rowLimit, state);
            if (fetched.overBudget() || state.bytesUsed > maxResultBytes) {
                state.truncation = DatabricksStatementClient.Truncation.BYTE_LIMIT;
                return;
            }
        }
    }

    private static List<ExternalLink> externalLinks(JsonNode array) {
        var links = new ArrayList<ExternalLink>(array.size());
        for (var link : array) {
            var url = link.path("external_link").asText(null);
            if (url != null && !url.isBlank()) {
                links.add(new ExternalLink(link.path("chunk_index").asInt(0),
                        link.path("row_count").asLong(0L), link.path("byte_count").asLong(0L),
                        url));
            }
        }
        return links;
    }

    /**
     * The next chunk index, from the envelope or — the {@code EXTERNAL_LINKS} shape — from the last
     * link in it. {@code null} when the result is complete.
     */
    private static Integer nextChunkIndex(JsonNode chunk) {
        var next = chunk.path("next_chunk_index");
        if (next.isNumber()) {
            return next.asInt();
        }
        var external = chunk.path("external_links");
        if (external.isArray() && !external.isEmpty()) {
            var last = external.get(external.size() - 1).path("next_chunk_index");
            if (last.isNumber()) {
                return last.asInt();
            }
        }
        return null;
    }

    // ---- row accumulation ------------------------------------------------------------------------

    private static void appendRows(JsonNode dataArray, List<List<String>> rows, Integer rowLimit,
                                   Budget state) {
        for (var row : dataArray) {
            if (rowCapReached(rows, rowLimit, state)) {
                return;
            }
            var values = new ArrayList<String>(row.size());
            for (var value : row) {
                values.add(value.isNull() ? null : value.asText());
            }
            rows.add(values);
        }
    }

    private static void appendRows(List<List<String>> source, List<List<String>> rows,
                                   Integer rowLimit, Budget state) {
        for (var row : source) {
            if (rowCapReached(rows, rowLimit, state)) {
                return;
            }
            rows.add(row);
        }
    }

    /** Stops streaming once the caller's {@code maxRows + 1} sentinel row has been collected. */
    private static boolean rowCapReached(List<List<String>> rows, Integer rowLimit, Budget state) {
        if (rowLimit == null || rows.size() < rowLimit) {
            return false;
        }
        if (state.truncation == DatabricksStatementClient.Truncation.NONE) {
            state.truncation = DatabricksStatementClient.Truncation.ROW_LIMIT;
        }
        return true;
    }
}
