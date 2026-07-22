package com.bablsoft.accessflow.engine.bigquery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single-pass lexer for GoogleSQL. Produces a flat token stream with enough structure for the
 * classifier ({@link BigQueryQueryParser}) and the WHERE-splice rewriter
 * ({@link BigQueryRowSecurityApplier}): each token carries its source offsets and the
 * paren/bracket nesting depth at which it appears (0 = top level). GoogleSQL specifics handled
 * here: line comments ({@code --}, {@code #}) and block comments; single- and double-quoted
 * strings with backslash escapes; triple-quoted strings ({@code '''…'''} / {@code """…"""});
 * raw/bytes string prefixes ({@code r''}, {@code b""}, {@code rb''}, …) best-effort as strings
 * (raw variants disable backslash escaping); and backtick-quoted identifiers — a single backtick
 * pair may span a whole dotted path ({@code `project.dataset.table`}), and {@code \`} escapes an
 * embedded backtick. Keywords inside any quoted run can never be mistaken for clause boundaries.
 * Unterminated literals/comments or unbalanced brackets raise {@link BigQueryParseException}
 * ({@code error.bigquery.unbalanced}).
 */
final class BigQuerySqlTokenizer {

    enum Kind { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL }

    private static final Set<String> STRING_PREFIXES = Set.of("R", "B", "RB", "BR");

    /**
     * @param kind  lexical class of the token
     * @param text  raw source slice (quotes/prefixes included for QUOTED_IDENT/STRING)
     * @param value normalized value — unquoted identifier text for QUOTED_IDENT, uppercase keyword
     *              for WORD, raw text otherwise
     * @param depth paren/bracket nesting depth at the token's start (0 = top level)
     * @param start inclusive start offset in the original statement text
     * @param end   exclusive end offset in the original statement text
     */
    record Token(Kind kind, String text, String value, int depth, int start, int end) {

        boolean isWord(String upper) {
            return kind == Kind.WORD && value.equals(upper);
        }

        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        /** The case-preserved identifier text (quotes stripped for backtick identifiers). */
        String identifier() {
            return kind == Kind.QUOTED_IDENT ? value : text;
        }
    }

    private BigQuerySqlTokenizer() {
    }

    /** Tokenize a full submission (possibly several {@code ;}-separated statements). */
    static List<Token> tokenize(String sql) {
        var tokens = new ArrayList<Token>();
        int depth = 0;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if ((c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') || c == '#') {
                i = lineCommentEnd(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = blockCommentEnd(sql, i);
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = stringEnd(sql, i, false);
                tokens.add(new Token(Kind.STRING, sql.substring(i, end), sql.substring(i, end),
                        depth, i, end));
                i = end;
                continue;
            }
            if (c == '`') {
                int end = backtickEnd(sql, i);
                var inner = sql.substring(i + 1, end - 1).replace("\\`", "`").replace("\\\\", "\\");
                tokens.add(new Token(Kind.QUOTED_IDENT, sql.substring(i, end), inner, depth, i, end));
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                var word = sql.substring(i, end);
                var upper = word.toUpperCase(Locale.ROOT);
                if (end < n && (sql.charAt(end) == '\'' || sql.charAt(end) == '"')
                        && STRING_PREFIXES.contains(upper)) {
                    // Raw/bytes string literal (r'…', b"…", rb'''…'''); raw disables backslashes.
                    int stringClose = stringEnd(sql, end, upper.indexOf('R') >= 0);
                    tokens.add(new Token(Kind.STRING, sql.substring(i, stringClose),
                            sql.substring(i, stringClose), depth, i, stringClose));
                    i = stringClose;
                    continue;
                }
                tokens.add(new Token(Kind.WORD, word, upper, depth, i, end));
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '.')) {
                    end++;
                }
                var num = sql.substring(i, end);
                tokens.add(new Token(Kind.NUMBER, num, num, depth, i, end));
                i = end;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                depth++;
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth < 0) {
                    throw new BigQueryParseException("error.bigquery.unbalanced");
                }
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
            i++;
        }
        if (depth != 0) {
            throw new BigQueryParseException("error.bigquery.unbalanced");
        }
        return tokens;
    }

    private static int lineCommentEnd(String sql, int from) {
        int end = sql.indexOf('\n', from);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int blockCommentEnd(String sql, int from) {
        int end = sql.indexOf("*/", from + 2);
        if (end < 0) {
            throw new BigQueryParseException("error.bigquery.unbalanced");
        }
        return end + 2;
    }

    /**
     * End offset (exclusive) of a quoted string starting at {@code from} (which must point at the
     * opening quote). Handles single- and triple-quoted forms; backslash escapes the next
     * character unless {@code raw}.
     */
    private static int stringEnd(String sql, int from, boolean raw) {
        char quote = sql.charAt(from);
        int n = sql.length();
        boolean triple = from + 2 < n && sql.charAt(from + 1) == quote && sql.charAt(from + 2) == quote;
        int i = from + (triple ? 3 : 1);
        while (i < n) {
            char c = sql.charAt(i);
            if (!raw && c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (!triple) {
                    return i + 1;
                }
                if (i + 2 < n && sql.charAt(i + 1) == quote && sql.charAt(i + 2) == quote) {
                    return i + 3;
                }
            }
            i++;
        }
        throw new BigQueryParseException("error.bigquery.unbalanced");
    }

    /** End offset (exclusive) of a backtick-quoted identifier; {@code \`} escapes a backtick. */
    private static int backtickEnd(String sql, int from) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '`') {
                return i + 1;
            }
            i++;
        }
        throw new BigQueryParseException("error.bigquery.unbalanced");
    }
}
