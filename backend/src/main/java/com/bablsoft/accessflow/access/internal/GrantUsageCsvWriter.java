package com.bablsoft.accessflow.access.internal;

/**
 * Minimal RFC-4180 CSV cell escaping for the over-provisioned access export (#625). Its own copy
 * because the Spring Modulith boundary forbids importing {@code audit.internal.CsvWriter}, exactly
 * as {@code compliance} and {@code attestation} each carry theirs.
 */
final class GrantUsageCsvWriter {

    private GrantUsageCsvWriter() {
    }

    /** Appends a CSV row (comma-separated, CRLF-terminated) with each cell escaped per RFC 4180. */
    static void appendRow(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i]));
        }
        sb.append("\r\n");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean mustQuote = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!mustQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
