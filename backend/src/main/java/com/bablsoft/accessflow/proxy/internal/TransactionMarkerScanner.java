package com.bablsoft.accessflow.proxy.internal;

/**
 * Lexical scanner that detects the BEGIN/COMMIT envelope around a multi-statement SQL submission.
 * JSqlParser 5.3 cannot parse {@code BEGIN} or {@code START TRANSACTION} as a statement (the
 * {@code BEGIN} token opens a {@code BEGIN…END} block), so the parser strips these markers
 * lexically before handing the body off to {@code CCJSqlParserUtil.parseStatements}.
 *
 * <p>Recognised opening forms (case-insensitive, optional trailing {@code ;}):
 * {@code BEGIN}, {@code BEGIN WORK}, {@code BEGIN TRANSACTION}, {@code START TRANSACTION}.
 *
 * <p>Recognised closing forms (case-insensitive, optional leading {@code ;}):
 * {@code COMMIT}, {@code COMMIT WORK}, {@code COMMIT TRANSACTION}, {@code END}.
 *
 * <p>Whitespace, {@code --…\n} line comments, and {@code /*…*\/} block comments around the
 * markers are tolerated. Nested block comments are not supported (matches JSqlParser).
 */
final class TransactionMarkerScanner {

    enum Kind {
        NONE,
        UNMATCHED_BEGIN,
        UNMATCHED_COMMIT,
        BOTH
    }

    record Boundary(Kind kind, int bodyStart, int bodyEnd) {
        static Boundary none() {
            return new Boundary(Kind.NONE, -1, -1);
        }
        static Boundary unmatchedBegin() {
            return new Boundary(Kind.UNMATCHED_BEGIN, -1, -1);
        }
        static Boundary unmatchedCommit() {
            return new Boundary(Kind.UNMATCHED_COMMIT, -1, -1);
        }
        static Boundary both(int bodyStart, int bodyEnd) {
            return new Boundary(Kind.BOTH, bodyStart, bodyEnd);
        }
    }

    private TransactionMarkerScanner() {
    }

    static Boundary scan(String sql) {
        int meaningfulStart = skipWhitespaceAndComments(sql, 0);
        int afterBegin = matchOpeningMarker(sql, meaningfulStart);
        boolean hasBegin = afterBegin > meaningfulStart;

        int meaningfulEnd = meaningfulEnd(sql);
        int beforeCommit = matchClosingMarker(sql, meaningfulEnd);
        boolean hasCommit = beforeCommit < meaningfulEnd;

        if (!hasBegin && !hasCommit) {
            return Boundary.none();
        }
        if (hasBegin && !hasCommit) {
            return Boundary.unmatchedBegin();
        }
        if (!hasBegin) {
            return Boundary.unmatchedCommit();
        }
        int bodyStart = consumeOptionalSemicolonForward(sql, afterBegin);
        int bodyEnd = consumeOptionalSemicolonBackward(sql, beforeCommit);
        if (bodyStart > bodyEnd) {
            return Boundary.both(bodyStart, bodyStart);
        }
        return Boundary.both(bodyStart, bodyEnd);
    }

    /**
     * Returns the smallest index {@code i} such that everything in {@code sql[i .. end)} is
     * whitespace or a comment, where {@code end} is the position just after the last meaningful
     * character. Line comments ({@code --…\n}) and block comments ({@code /*…*\/}) are skipped.
     */
    private static int meaningfulEnd(String sql) {
        int n = sql.length();
        int last = -1;
        int i = 0;
        while (i < n) {
            int next = skipWhitespaceAndComments(sql, i);
            if (next > i) {
                i = next;
                continue;
            }
            if (i >= n) {
                break;
            }
            last = i;
            i++;
        }
        return last + 1;
    }

    private static int skipWhitespaceAndComments(String sql, int from) {
        int n = sql.length();
        int i = from;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            break;
        }
        return i;
    }

    private static int consumeOptionalSemicolonForward(String sql, int from) {
        int i = skipWhitespaceAndComments(sql, from);
        if (i < sql.length() && sql.charAt(i) == ';') {
            return i + 1;
        }
        return from;
    }

    /**
     * Walks backward from {@code end}, skipping any trailing semicolon that separates the
     * transaction body from the closing marker. Returns the new end of the body.
     */
    private static int consumeOptionalSemicolonBackward(String sql, int end) {
        int i = end;
        while (i > 0 && Character.isWhitespace(sql.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && sql.charAt(i - 1) == ';') {
            return i - 1;
        }
        return end;
    }

    /**
     * Returns the index just past the opening marker, or {@code from} if none matches.
     */
    private static int matchOpeningMarker(String sql, int from) {
        int afterStart = matchKeyword(sql, from, "START");
        if (afterStart > from) {
            int afterStartWs = skipWhitespaceAndComments(sql, afterStart);
            int afterTransaction = matchKeyword(sql, afterStartWs, "TRANSACTION");
            if (afterTransaction > afterStartWs) {
                return afterTransaction;
            }
            return from;
        }
        int afterBegin = matchKeyword(sql, from, "BEGIN");
        if (afterBegin == from) {
            return from;
        }
        int afterBeginWs = skipWhitespaceAndComments(sql, afterBegin);
        int afterWork = matchKeyword(sql, afterBeginWs, "WORK");
        if (afterWork > afterBeginWs) {
            return afterWork;
        }
        int afterTransaction = matchKeyword(sql, afterBeginWs, "TRANSACTION");
        if (afterTransaction > afterBeginWs) {
            return afterTransaction;
        }
        return afterBegin;
    }

    /**
     * Returns the index where the closing marker begins, or {@code end} if none matches.
     * Handles {@code COMMIT}, {@code COMMIT WORK}, {@code COMMIT TRANSACTION}, {@code END},
     * tolerating a trailing semicolon and surrounding whitespace/comments.
     */
    private static int matchClosingMarker(String sql, int end) {
        int lastWordEnd = end;
        if (lastWordEnd > 0 && sql.charAt(lastWordEnd - 1) == ';') {
            lastWordEnd--;
            while (lastWordEnd > 0 && Character.isWhitespace(sql.charAt(lastWordEnd - 1))) {
                lastWordEnd--;
            }
        }
        int lastWordStart = backwardWordStart(sql, lastWordEnd);
        if (lastWordStart == lastWordEnd) {
            return end;
        }
        String lastWord = sql.substring(lastWordStart, lastWordEnd);
        if (lastWord.equalsIgnoreCase("END") || lastWord.equalsIgnoreCase("COMMIT")) {
            return lastWordStart;
        }
        if (lastWord.equalsIgnoreCase("WORK") || lastWord.equalsIgnoreCase("TRANSACTION")) {
            int beforeWordEnd = lastWordStart;
            while (beforeWordEnd > 0 && Character.isWhitespace(sql.charAt(beforeWordEnd - 1))) {
                beforeWordEnd--;
            }
            int beforeWordStart = backwardWordStart(sql, beforeWordEnd);
            if (beforeWordStart < beforeWordEnd) {
                String previous = sql.substring(beforeWordStart, beforeWordEnd);
                if (previous.equalsIgnoreCase("COMMIT")) {
                    return beforeWordStart;
                }
            }
        }
        return end;
    }

    private static int backwardWordStart(String sql, int end) {
        int i = end;
        while (i > 0 && isWordChar(sql.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int matchKeyword(String sql, int from, String keyword) {
        int n = keyword.length();
        if (from + n > sql.length()) {
            return from;
        }
        if (!sql.regionMatches(true, from, keyword, 0, n)) {
            return from;
        }
        if (from + n < sql.length() && isWordChar(sql.charAt(from + n))) {
            return from;
        }
        return from + n;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
