package com.bablsoft.accessflow.compliance.internal;

import org.springframework.stereotype.Component;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Renders a persisted query-result snapshot as an RFC 4180 CSV document (#626). When the export
 * decision requires a watermark, the header/footer lines from
 * {@code compliance.api.ResultExportWatermark} are emitted as single-cell first/last records —
 * RFC 4180 has no comment syntax, and a single-cell row is the least intrusive representation.
 * Owns its own escaping copy because {@code audit.internal.CsvWriter} cannot be imported across
 * the Spring Modulith boundary.
 */
@Component
class ResultExportCsvWriter {

    private static final String LINE = "\r\n";

    byte[] write(List<String> columnNames, List<List<Object>> rows, String watermarkHeader,
                 String watermarkFooter) {
        var sb = new StringBuilder();
        if (watermarkHeader != null) {
            writeRow(sb, List.of(watermarkHeader));
        }
        writeRow(sb, columnNames);
        for (var row : rows) {
            writeRow(sb, row.stream().map(ResultExportCsvWriter::str).toList());
        }
        if (watermarkFooter != null) {
            writeRow(sb, List.of(watermarkFooter));
        }
        return sb.toString().getBytes(UTF_8);
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void writeRow(StringBuilder sb, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        sb.append(LINE);
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean mustQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                mustQuote = true;
                break;
            }
        }
        if (!mustQuote) {
            return value;
        }
        var sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
