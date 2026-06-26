package com.bablsoft.accessflow.attestation.internal;

/** Minimal RFC-4180 CSV cell escaping for the evidence export (the audit module's writer is private). */
final class AttestationCsvWriter {

    private AttestationCsvWriter() {
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
