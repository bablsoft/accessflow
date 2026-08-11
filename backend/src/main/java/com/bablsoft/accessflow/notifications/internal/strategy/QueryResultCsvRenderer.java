package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders a query's persisted result snapshot as an RFC 4180 CSV for email attachment (#627 —
 * recurring occurrence result delivery). Values come from {@code query_request_results}, which is
 * already post-mask (restricted columns stored as {@code ***}), so the attachment can never leak a
 * value the in-app results table would have hidden. Row-capped at {@link #MAX_ATTACHMENT_ROWS}
 * with a trailing note row, mirroring the query-list CSV export's cap-and-flag convention.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueryResultCsvRenderer {

    static final int MAX_ATTACHMENT_ROWS = 10_000;

    private final QueryResultPersistenceService queryResultPersistenceService;
    private final ObjectMapper objectMapper;

    public record Csv(byte[] content, String filename) {
    }

    /**
     * @return the rendered CSV, or empty when the query has no stored result snapshot (non-SELECT
     *         occurrences, failed executions) or the stored JSON cannot be parsed.
     */
    public Optional<Csv> render(UUID queryRequestId) {
        var snapshot = queryResultPersistenceService.find(queryRequestId).orElse(null);
        if (snapshot == null) {
            return Optional.empty();
        }
        try {
            JsonNode columns = objectMapper.readTree(snapshot.columnsJson());
            JsonNode rows = objectMapper.readTree(snapshot.rowsJson());
            var sb = new StringBuilder();
            var header = new java.util.ArrayList<String>(columns.size());
            for (JsonNode column : columns) {
                header.add(column.path("name").asString());
            }
            writeRow(sb, header);
            int written = 0;
            for (JsonNode row : rows) {
                if (written >= MAX_ATTACHMENT_ROWS) {
                    writeRow(sb, List.of("… truncated at " + MAX_ATTACHMENT_ROWS + " rows"));
                    break;
                }
                var cells = new java.util.ArrayList<String>(row.size());
                for (JsonNode cell : row) {
                    // Scalar cells render bare; nested structures (document engines) as raw JSON.
                    cells.add(cell.isNull() ? ""
                            : cell.isValueNode() ? cell.asString() : cell.toString());
                }
                writeRow(sb, cells);
                written++;
            }
            return Optional.of(new Csv(sb.toString().getBytes(StandardCharsets.UTF_8),
                    "results-" + queryRequestId + ".csv"));
        } catch (RuntimeException ex) {
            log.error("Failed to render results CSV for query {}", queryRequestId, ex);
            return Optional.empty();
        }
    }

    // RFC 4180: quote a field when it contains a comma, quote, CR or LF; double embedded quotes.
    private static void writeRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
