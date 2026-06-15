package com.bablsoft.accessflow.engine.neo4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass lexer for Cypher. Produces a flat token stream with enough structure for the
 * classifier ({@link CypherQueryParser}) and the WHERE-splice rewriter
 * ({@link Neo4jRowSecurityApplier}): each token carries its source offsets, the bracket nesting
 * depth, and the innermost enclosing bracket character ({@code (}, {@code [}, {@code &#123;} or
 * {@code 0} at top level) so a label colon inside a node/relationship pattern can be told apart
 * from a map-literal key colon. Comments ({@code //} and {@code /* … *}{@code /}) are skipped;
 * string literals ({@code '…'} / {@code "…"}, with backslash escapes) and backtick-quoted
 * identifiers ({@code `…`}, with {@code ``} doubling) are single tokens, so keywords inside them can
 * never be mistaken for clause boundaries. Unlike SQL, {@code --} is NOT a comment in Cypher (it is
 * relationship syntax). Unterminated literals/comments or unbalanced brackets raise
 * {@link CypherParseException} ({@code error.neo4j.unbalanced}).
 */
final class CypherTokenizer {

    enum Kind { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL }

    /**
     * @param kind      lexical class of the token
     * @param text      raw source slice (quotes/backticks included for QUOTED_IDENT/STRING)
     * @param value     normalized value — unquoted identifier text for QUOTED_IDENT, uppercase
     *                  keyword for WORD, raw text otherwise
     * @param depth     bracket nesting depth at the token's start (0 = top level)
     * @param enclosing innermost open bracket char at the token's start ({@code (}/{@code [}/{@code &#123;}),
     *                  or {@code 0} when at top level
     * @param start     inclusive start offset in the original statement text
     * @param end       exclusive end offset in the original statement text
     */
    record Token(Kind kind, String text, String value, int depth, char enclosing, int start, int end) {

        boolean isWord(String upper) {
            return kind == Kind.WORD && value.equals(upper);
        }

        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        boolean isIdentifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED_IDENT;
        }

        /** The Cypher identifier text: case-preserved (Cypher is case-sensitive for identifiers). */
        String identifier() {
            return kind == Kind.QUOTED_IDENT ? value : text;
        }
    }

    private CypherTokenizer() {
    }

    /** Tokenize a full submission (possibly several {@code ;}-separated statements). */
    static List<Token> tokenize(String cypher) {
        var tokens = new ArrayList<Token>();
        Deque<Character> brackets = new ArrayDeque<>();
        int i = 0;
        int n = cypher.length();
        while (i < n) {
            char c = cypher.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && cypher.charAt(i + 1) == '/') {
                i = lineCommentEnd(cypher, i);
                continue;
            }
            if (c == '/' && i + 1 < n && cypher.charAt(i + 1) == '*') {
                i = blockCommentEnd(cypher, i);
                continue;
            }
            int depth = brackets.size();
            char enclosing = brackets.isEmpty() ? 0 : brackets.peek();
            if (c == '\'' || c == '"') {
                int end = stringEnd(cypher, i, c);
                tokens.add(new Token(Kind.STRING, cypher.substring(i, end), cypher.substring(i, end),
                        depth, enclosing, i, end));
                i = end;
                continue;
            }
            if (c == '`') {
                int end = backtickEnd(cypher, i);
                var inner = cypher.substring(i + 1, end - 1).replace("``", "`");
                tokens.add(new Token(Kind.QUOTED_IDENT, cypher.substring(i, end), inner, depth,
                        enclosing, i, end));
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(cypher.charAt(end)) || cypher.charAt(end) == '_')) {
                    end++;
                }
                var word = cypher.substring(i, end);
                tokens.add(new Token(Kind.WORD, word, word.toUpperCase(Locale.ROOT), depth, enclosing,
                        i, end));
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(cypher.charAt(end)) || cypher.charAt(end) == '.')) {
                    end++;
                }
                var num = cypher.substring(i, end);
                tokens.add(new Token(Kind.NUMBER, num, num, depth, enclosing, i, end));
                i = end;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth,
                        enclosing, i, i + 1));
                brackets.push(c);
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                if (brackets.isEmpty() || !matches(brackets.peek(), c)) {
                    throw new CypherParseException("error.neo4j.unbalanced");
                }
                brackets.pop();
                int afterDepth = brackets.size();
                char afterEnclosing = brackets.isEmpty() ? 0 : brackets.peek();
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), afterDepth,
                        afterEnclosing, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, enclosing,
                    i, i + 1));
            i++;
        }
        if (!brackets.isEmpty()) {
            throw new CypherParseException("error.neo4j.unbalanced");
        }
        return tokens;
    }

    private static boolean matches(char open, char close) {
        return (open == '(' && close == ')')
                || (open == '[' && close == ']')
                || (open == '{' && close == '}');
    }

    private static int lineCommentEnd(String cypher, int from) {
        int end = cypher.indexOf('\n', from);
        return end < 0 ? cypher.length() : end + 1;
    }

    private static int blockCommentEnd(String cypher, int from) {
        int end = cypher.indexOf("*/", from + 2);
        if (end < 0) {
            throw new CypherParseException("error.neo4j.unbalanced");
        }
        return end + 2;
    }

    /** End offset (exclusive) of a {@code '}/{@code "} string starting at {@code from}; backslash escapes. */
    private static int stringEnd(String cypher, int from, char quote) {
        int i = from + 1;
        int n = cypher.length();
        while (i < n) {
            char c = cypher.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        throw new CypherParseException("error.neo4j.unbalanced");
    }

    /** End offset (exclusive) of a backtick identifier starting at {@code from}; {@code ``} doubling. */
    private static int backtickEnd(String cypher, int from) {
        int i = from + 1;
        int n = cypher.length();
        while (i < n) {
            char c = cypher.charAt(i);
            if (c == '`') {
                if (i + 1 < n && cypher.charAt(i + 1) == '`') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new CypherParseException("error.neo4j.unbalanced");
    }
}
