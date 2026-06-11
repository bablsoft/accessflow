package com.bablsoft.accessflow.engine.couchbase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass lexer for SQL++ (N1QL). Produces a flat token stream with enough structure for the
 * classifier ({@link CouchbaseQueryParser}) and the WHERE-splice rewriter
 * ({@link CouchbaseRowSecurityApplier}): each token carries its source offsets and the
 * paren/bracket/brace nesting depth at which it appears. Comments ({@code --} and
 * {@code /* … *}{@code /}) are skipped; string literals ({@code '…'} / {@code "…"}, with doubling
 * and backslash escapes) and backtick-escaped identifiers are single tokens, so keywords inside
 * them can never be mistaken for clause boundaries. Unterminated literals/comments or unbalanced
 * brackets raise {@link CouchbaseParseException} ({@code error.couchbase.unbalanced}).
 */
final class SqlPlusPlusTokenizer {

    enum Kind { WORD, QUOTED_IDENT, STRING, NUMBER, PARAM, SYMBOL }

    /**
     * @param kind  lexical class of the token
     * @param text  raw source slice (backticks/quotes included for QUOTED_IDENT/STRING)
     * @param value normalized value — unquoted identifier text for QUOTED_IDENT, uppercase word
     *              for WORD, raw text otherwise
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
    }

    private SqlPlusPlusTokenizer() {
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
                int end = quotedEnd(sql, i, c, true);
                tokens.add(new Token(Kind.STRING, sql.substring(i, end), sql.substring(i, end),
                        depth, i, end));
                i = end;
                continue;
            }
            if (c == '`') {
                int end = quotedEnd(sql, i, '`', false);
                var inner = sql.substring(i + 1, end - 1).replace("``", "`");
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
            if (c == '$' && i + 1 < n
                    && (Character.isLetterOrDigit(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                var param = sql.substring(i, end);
                tokens.add(new Token(Kind.PARAM, param, param, depth, i, end));
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
                    throw new CouchbaseParseException("error.couchbase.unbalanced");
                }
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
            i++;
        }
        if (depth != 0) {
            throw new CouchbaseParseException("error.couchbase.unbalanced");
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
            throw new CouchbaseParseException("error.couchbase.unbalanced");
        }
        return end + 2;
    }

    /** End offset (exclusive) of a quoted run starting at {@code from}; handles doubling, and
     *  backslash escapes for string literals. */
    private static int quotedEnd(String sql, int from, char quote, boolean backslashEscapes) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < n) {
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
        throw new CouchbaseParseException("error.couchbase.unbalanced");
    }
}
