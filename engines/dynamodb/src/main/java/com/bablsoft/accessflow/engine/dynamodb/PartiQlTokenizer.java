package com.bablsoft.accessflow.engine.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass lexer for DynamoDB PartiQL. Produces a flat token stream with enough structure for the
 * classifier ({@link PartiQlQueryParser}) and the WHERE-splice rewriter
 * ({@link DynamoDbRowSecurityApplier}): each token carries its source offsets and the
 * paren/bracket/brace nesting depth at which it appears (so the {@code {…}} map and {@code […]} list
 * literals of an {@code INSERT … VALUE} are tracked, and the {@code ?} placeholder is a single
 * symbol). Comments ({@code --}, {@code //}, {@code /* … *}{@code /}) are skipped; single-quoted
 * strings (with {@code ''} doubling) and double-quoted identifiers (with {@code ""} doubling) are
 * single tokens, so keywords inside them can never be mistaken for clause boundaries. Unterminated
 * literals/comments or unbalanced brackets raise {@link PartiQlParseException}
 * ({@code error.dynamodb.unbalanced}).
 */
final class PartiQlTokenizer {

    enum Kind { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL }

    /**
     * @param kind  lexical class of the token
     * @param text  raw source slice (quotes included for QUOTED_IDENT/STRING)
     * @param value normalized value — unquoted identifier text for QUOTED_IDENT, uppercase keyword
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

        /** The case-preserved table/attribute identifier (DynamoDB names are case-sensitive). */
        String identifier() {
            return kind == Kind.QUOTED_IDENT ? value : text;
        }
    }

    private PartiQlTokenizer() {
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
            if ((c == '-' && i + 1 < n && sql.charAt(i + 1) == '-')
                    || (c == '/' && i + 1 < n && sql.charAt(i + 1) == '/')) {
                i = lineCommentEnd(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = blockCommentEnd(sql, i);
                continue;
            }
            if (c == '\'') {
                int end = quotedEnd(sql, i, '\'');
                tokens.add(new Token(Kind.STRING, sql.substring(i, end), sql.substring(i, end),
                        depth, i, end));
                i = end;
                continue;
            }
            if (c == '"') {
                int end = quotedEnd(sql, i, '"');
                var inner = sql.substring(i + 1, end - 1).replace("\"\"", "\"");
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
            if (c == '(' || c == '[' || c == '{') {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                depth++;
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth < 0) {
                    throw new PartiQlParseException("error.dynamodb.unbalanced");
                }
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
            i++;
        }
        if (depth != 0) {
            throw new PartiQlParseException("error.dynamodb.unbalanced");
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
            throw new PartiQlParseException("error.dynamodb.unbalanced");
        }
        return end + 2;
    }

    /** End offset (exclusive) of a quoted run starting at {@code from}; handles {@code ''}/{@code ""} doubling. */
    private static int quotedEnd(String sql, int from, char quote) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new PartiQlParseException("error.dynamodb.unbalanced");
    }
}
