package com.bablsoft.accessflow.schemachange.internal;

import java.util.Locale;

/**
 * JDK-only shape checks the authoring gate (#879) runs before the engine-aware parser: a change
 * set's statement must be exactly one statement and must not open a transaction — each statement
 * runs on its own, autocommit, so a {@code BEGIN … COMMIT} envelope would promise atomicity the
 * executor cannot deliver. The proxy's own marker scanner is module-private, and these two checks
 * exist so the refusal names the change-set context instead of leaking a query-shaped error.
 */
final class SchemaChangeStatementScanner {

    private SchemaChangeStatementScanner() {
    }

    /**
     * True when the first token after leading whitespace and comments is {@code BEGIN} (bare,
     * {@code BEGIN WORK}, {@code BEGIN TRANSACTION}) or {@code START TRANSACTION}. {@code DO $$
     * BEGIN … END $$} starts with {@code DO}, so a procedural block is not an envelope.
     */
    static boolean startsWithTransactionMarker(String sql) {
        var body = skipLeadingWhitespaceAndComments(sql);
        var first = leadingWord(body);
        if (first.equals("BEGIN")) {
            return true;
        }
        if (first.equals("START")) {
            var rest = body.substring(first.length());
            return leadingWord(skipLeadingWhitespaceAndComments(rest)).equals("TRANSACTION");
        }
        return false;
    }

    /**
     * True when a {@code ;} appears outside single-quoted literals (with {@code ''} escapes),
     * double-quoted or back-quoted identifiers, line and block comments and {@code $tag$ … $tag$}
     * dollar-quoted blocks, and is followed by anything but
     * whitespace and comments — a trailing terminator is not a second statement.
     */
    static boolean containsStatementSeparator(String sql) {
        var i = 0;
        var n = sql.length();
        while (i < n) {
            var c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '$') {
                i = skipDollarQuoted(sql, i);
            } else if (c == ';') {
                if (!skipLeadingWhitespaceAndComments(sql.substring(i + 1)).isEmpty()) {
                    return true;
                }
                i++;
            } else {
                i++;
            }
        }
        return false;
    }

    private static String skipLeadingWhitespaceAndComments(String sql) {
        var i = 0;
        var n = sql.length();
        while (i < n) {
            var c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
            } else {
                break;
            }
        }
        return sql.substring(i);
    }

    private static String leadingWord(String s) {
        var end = 0;
        while (end < s.length() && Character.isLetter(s.charAt(end))) {
            end++;
        }
        return s.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static int skipQuoted(String sql, int start, char quote) {
        var i = start + 1;
        var n = sql.length();
        while (i < n) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static int skipLineComment(String sql, int start) {
        var end = sql.indexOf('\n', start);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int skipBlockComment(String sql, int start) {
        var end = sql.indexOf("*/", start + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    /** Skips {@code $tag$ … $tag$}; a lone {@code $} that opens no tag (a positional {@code $1}) is one char. */
    private static int skipDollarQuoted(String sql, int start) {
        var n = sql.length();
        var tagEnd = start + 1;
        while (tagEnd < n && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_')) {
            tagEnd++;
        }
        if (tagEnd >= n || sql.charAt(tagEnd) != '$'
                || (tagEnd > start + 1 && Character.isDigit(sql.charAt(start + 1)))) {
            return start + 1;
        }
        var tag = sql.substring(start, tagEnd + 1);
        var close = sql.indexOf(tag, tagEnd + 1);
        return close < 0 ? n : close + tag.length();
    }
}
