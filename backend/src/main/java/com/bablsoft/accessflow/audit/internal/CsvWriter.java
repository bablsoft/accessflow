package com.bablsoft.accessflow.audit.internal;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Minimal RFC 4180 CSV writer. Wraps fields in double quotes when they contain a comma, quote,
 * CR, or LF; escapes embedded quotes by doubling them. Null fields are emitted as empty.
 *
 * <p>Mirrors {@code workflow.internal.CsvWriter}; the two copies coexist because Spring Modulith
 * forbids cross-module {@code internal} imports. A shared utility module is not justified for a
 * single 50-line pure-JDK helper.
 */
final class CsvWriter {

    static final String LINE_TERMINATOR = "\r\n";

    private CsvWriter() {
    }

    static void writeRow(Writer writer, List<String> fields) throws IOException {
        var sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        sb.append(LINE_TERMINATOR);
        writer.write(sb.toString());
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
        var sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
