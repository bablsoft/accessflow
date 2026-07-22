package com.bablsoft.accessflow.engine.databricks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass lexer for Databricks SQL (the Spark SQL family). Produces a flat token stream with
 * enough structure for the classifier ({@link DatabricksQueryParser}) and the WHERE-splice
 * rewriter ({@link DatabricksRowSecurityApplier}): each token carries its source offsets and the
 * paren/bracket/brace nesting depth at which it appears. Comments ({@code --} line and
 * {@code /* … *}{@code /} block), single- and double-quoted strings (both with backslash escapes
 * and quote doubling), and backtick-quoted identifiers (with {@code ``} doubling) are single
 * tokens, so keywords inside them can never be mistaken for clause boundaries. A {@code :name}
 * named-parameter marker is lexed as one {@link Kind#NAMED_PARAM} token (the classifier rejects
 * it), while the {@code ::} cast shorthand is a single {@code ::} symbol and a bare {@code :} is a
 * plain symbol — neither counts as a parameter marker. Unterminated literals/comments or
 * unbalanced brackets raise {@link DatabricksParseException} ({@code error.databricks.unbalanced}).
 */
final class DatabricksSqlTokenizer {

    enum Kind { WORD, BACKTICK_IDENT, STRING, NUMBER, SYMBOL, NAMED_PARAM }

    /**
     * @param kind  lexical class of the token
     * @param text  raw source slice (quotes/backticks included for STRING/BACKTICK_IDENT)
     * @param value normalized value — unquoted identifier text for BACKTICK_IDENT, uppercase
     *              keyword for WORD, raw text otherwise
     * @param depth paren/bracket/brace nesting depth at the token's start (0 = top level)
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

        /** The raw identifier text (backticks stripped for a quoted identifier). */
        String identifier() {
            return kind == Kind.BACKTICK_IDENT ? value : text;
        }
    }

    private DatabricksSqlTokenizer() {
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
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i = lineCommentEnd(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = blockCommentEnd(sql, i);
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = stringEnd(sql, i, c);
                tokens.add(new Token(Kind.STRING, sql.substring(i, end), sql.substring(i, end),
                        depth, i, end));
                i = end;
                continue;
            }
            if (c == '`') {
                int end = backtickEnd(sql, i);
                var inner = sql.substring(i + 1, end - 1).replace("``", "`");
                tokens.add(new Token(Kind.BACKTICK_IDENT, sql.substring(i, end), inner, depth, i, end));
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                var word = sql.substring(i, end);
                tokens.add(new Token(Kind.WORD, word, word.toUpperCase(Locale.ROOT), depth, i, end));
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
            if (c == ':') {
                // '::' is the cast shorthand (a single symbol, never a parameter marker); a ':'
                // directly followed by an identifier start is a named-parameter marker.
                if (i + 1 < n && sql.charAt(i + 1) == ':') {
                    tokens.add(new Token(Kind.SYMBOL, "::", "::", depth, i, i + 2));
                    i += 2;
                    continue;
                }
                if (i + 1 < n && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                    int end = i + 2;
                    while (end < n && (Character.isLetterOrDigit(sql.charAt(end))
                            || sql.charAt(end) == '_')) {
                        end++;
                    }
                    var text = sql.substring(i, end);
                    tokens.add(new Token(Kind.NAMED_PARAM, text, text, depth, i, end));
                    i = end;
                    continue;
                }
                tokens.add(new Token(Kind.SYMBOL, ":", ":", depth, i, i + 1));
                i++;
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
                    throw new DatabricksParseException("error.databricks.unbalanced");
                }
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
            i++;
        }
        if (depth != 0) {
            throw new DatabricksParseException("error.databricks.unbalanced");
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
            throw new DatabricksParseException("error.databricks.unbalanced");
        }
        return end + 2;
    }

    /**
     * End offset (exclusive) of a string literal starting at {@code from}. Handles both quote
     * doubling ({@code ''} / {@code ""}) and backslash escapes ({@code \'} / {@code \"} — Spark
     * SQL strings are backslash-escaped by default).
     */
    private static int stringEnd(String sql, int from, char quote) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new DatabricksParseException("error.databricks.unbalanced");
    }

    /** End offset (exclusive) of a backtick-quoted identifier; handles {@code ``} doubling. */
    private static int backtickEnd(String sql, int from) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '`') {
                if (i + 1 < n && sql.charAt(i + 1) == '`') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new DatabricksParseException("error.databricks.unbalanced");
    }
}
