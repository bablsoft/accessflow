package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.engine.snowflake.SnowflakeSqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.snowflake.SnowflakeSqlTokenizer.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and classifies a submitted Snowflake SQL statement without a full AST: a keyword
 * classifier over the {@link SnowflakeSqlTokenizer} token stream, mirroring the JSqlParser path's
 * guarantees. Exactly one statement per submission (a single trailing {@code ;} is fine).
 * Classification: {@code SELECT} (including a bare parenthesized select) → SELECT; {@code INSERT}
 * → INSERT; {@code UPDATE} → UPDATE; {@code DELETE} → DELETE; {@code MERGE} → UPDATE;
 * {@code TRUNCATE} and {@code CREATE}/{@code ALTER}/{@code DROP} of TABLE / VIEW / SCHEMA /
 * DATABASE (incl. {@code OR REPLACE}, {@code MATERIALIZED}, {@code TRANSIENT}/{@code TEMPORARY}/
 * {@code TEMP} forms) → DDL. A leading {@code WITH} is classified by the final top-level verb
 * after the CTE list. Everything else fails closed with {@link InvalidSqlException} (HTTP 422) —
 * scripting/session/data-movement verbs ({@code CALL}, {@code EXECUTE}, {@code BEGIN},
 * {@code COPY}, {@code PUT}, {@code USE}, {@code SET}, …) and {@code CREATE}/{@code ALTER}/
 * {@code DROP} of PROCEDURE / FUNCTION / TASK / STREAM / PIPE / STAGE / WAREHOUSE / USER / ROLE /
 * INTEGRATION / SHARE. A top-level {@code ?} placeholder is rejected up front — positional binds
 * are reserved for the row-security splice. {@code referencedTables} carries every table read or
 * written (subselects included, CTE names excluded), normalized to lowercase dot-joined form for
 * the host's allow-list check.
 */
class SnowflakeQueryParser {

    private static final Set<String> REJECTED_VERBS = Set.of(
            "CALL", "EXECUTE", "BEGIN", "DECLARE", "PUT", "GET", "COPY", "UNLOAD", "USE", "SHOW",
            "DESCRIBE", "DESC", "GRANT", "REVOKE", "UNDROP", "COMMENT", "SET", "UNSET", "LIST",
            "REMOVE");
    private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP");
    private static final Set<String> DDL_OBJECTS = Set.of("TABLE", "VIEW", "SCHEMA", "DATABASE");
    private static final Set<String> DDL_MODIFIERS = Set.of(
            "TRANSIENT", "TEMPORARY", "TEMP", "MATERIALIZED");
    /** Unquoted words that can never open a table reference (a real table so named is quoted). */
    private static final Set<String> NON_TABLE_WORDS = Set.of(
            "SET", "VALUES", "SELECT", "WHERE", "LATERAL", "ON", "USING", "AS", "JOIN");
    /** Clause keywords that end alias consumption in a FROM table list. */
    private static final Set<String> REF_LIST_STOP = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "ORDER", "LIMIT", "OFFSET", "FETCH", "JOIN",
            "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "ON", "USING", "UNION",
            "INTERSECT", "EXCEPT", "MINUS", "SET", "WHEN", "MATCHED", "AND", "OR", "SAMPLE",
            "TABLESAMPLE", "AT", "BEFORE", "CHANGES", "PIVOT", "UNPIVOT", "RETURNING");

    private final EngineMessages messages;

    SnowflakeQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result for the workflow layer (query type, tables, routing hints). */
    SqlParseResult parse(String query) {
        var statement = parseStatement(query);
        return new SqlParseResult(statement.kind().queryType(), false, List.of(query),
                statement.tables(), statement.hasWhere(), statement.hasLimit());
    }

    /** Full parse to the executable {@link SnowflakeStatement}; reused by the executor. */
    SnowflakeStatement parseStatement(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.snowflake.blank");
        }
        try {
            var tokens = singleStatement(SnowflakeSqlTokenizer.tokenize(query));
            rejectPlaceholders(tokens);
            return classify(query, tokens);
        } catch (SnowflakeParseException ex) {
            throw invalid(ex.messageKey(), ex.args());
        }
    }

    // ---- statement splitting ----------------------------------------------------------------

    private static List<Token> singleStatement(List<Token> tokens) {
        var statements = new ArrayList<List<Token>>();
        var current = new ArrayList<Token>();
        for (var token : tokens) {
            if (token.depth() == 0 && token.isSymbol(";")) {
                if (!current.isEmpty()) {
                    statements.add(current);
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(token);
        }
        if (!current.isEmpty()) {
            statements.add(current);
        }
        if (statements.isEmpty()) {
            throw new SnowflakeParseException("error.snowflake.blank");
        }
        if (statements.size() > 1) {
            throw new SnowflakeParseException("error.snowflake.multiple_statements");
        }
        return statements.get(0);
    }

    private static void rejectPlaceholders(List<Token> tokens) {
        for (var token : tokens) {
            if (token.isSymbol("?")) {
                throw new SnowflakeParseException("error.snowflake.placeholders_forbidden");
            }
        }
    }

    // ---- classification ---------------------------------------------------------------------

    private SnowflakeStatement classify(String sql, List<Token> tokens) {
        var cteNames = new LinkedHashSet<String>();
        int i = 0;
        boolean hasCte = false;
        if (tokens.get(0).isWord("WITH")) {
            hasCte = true;
            i = skipCteList(tokens, cteNames);
        }
        int afterCte = i;
        while (i < tokens.size() && tokens.get(i).isSymbol("(")) {
            i++;
        }
        if (i >= tokens.size()) {
            throw new SnowflakeParseException("error.snowflake.blank");
        }
        var first = tokens.get(i);
        if (first.kind() != Kind.WORD) {
            throw new SnowflakeParseException("error.snowflake.unsupported_statement", first.text());
        }
        if (i > afterCte && !first.isWord("SELECT")) {
            // A bare parenthesized statement is only accepted for SELECT.
            throw new SnowflakeParseException("error.snowflake.unsupported_statement", first.text());
        }
        if (REJECTED_VERBS.contains(first.value())) {
            throw new SnowflakeParseException("error.snowflake.unsupported_statement", first.text());
        }
        int mainDepth = first.depth();
        var kind = switch (first.value()) {
            case "SELECT" -> SnowflakeStatementKind.SELECT;
            case "INSERT" -> SnowflakeStatementKind.INSERT;
            case "UPDATE" -> SnowflakeStatementKind.UPDATE;
            case "DELETE" -> SnowflakeStatementKind.DELETE;
            case "MERGE" -> SnowflakeStatementKind.MERGE;
            case "TRUNCATE", "CREATE", "ALTER", "DROP" -> SnowflakeStatementKind.DDL;
            default -> throw new SnowflakeParseException(
                    "error.snowflake.unsupported_statement", first.text());
        };
        var target = switch (first.value()) {
            case "SELECT", "DELETE" -> refAfterWord(tokens, i, "FROM", mainDepth, true);
            case "INSERT", "MERGE" -> refAfterWord(tokens, i, "INTO", mainDepth, false);
            case "UPDATE" -> dottedRef(tokens, i + 1);
            case "TRUNCATE" -> truncateTarget(tokens, i);
            default -> ddlTarget(tokens, i, first.value());
        };
        if (target == null && kind != SnowflakeStatementKind.SELECT) {
            throw new SnowflakeParseException("error.snowflake.table_required");
        }
        var tables = collectTables(tokens, cteNames, target);
        boolean hasWhere = hasWordAtDepth(tokens, "WHERE", mainDepth);
        boolean hasLimit = hasWordAtDepth(tokens, "LIMIT", mainDepth)
                || hasWordAtDepth(tokens, "FETCH", mainDepth);
        return new SnowflakeStatement(sql, kind, target, tables, hasCte, hasWhere, hasLimit,
                List.copyOf(tokens));
    }

    /**
     * Skips the {@code WITH} CTE list, collecting the (lowercased) CTE names so they can be
     * excluded from {@code referencedTables}. Returns the index of the main statement's verb.
     */
    private static int skipCteList(List<Token> tokens, Set<String> cteNames) {
        int i = 1;
        if (i < tokens.size() && tokens.get(i).isWord("RECURSIVE")) {
            i++;
        }
        while (true) {
            if (i >= tokens.size() || !isIdentifier(tokens.get(i))) {
                throw new SnowflakeParseException("error.snowflake.unsupported_statement", "WITH");
            }
            cteNames.add(tokens.get(i).identifier().toLowerCase(Locale.ROOT));
            i++;
            if (i < tokens.size() && tokens.get(i).isSymbol("(")) {
                i = skipParens(tokens, i); // optional column list
            }
            if (i >= tokens.size() || !tokens.get(i).isWord("AS")) {
                throw new SnowflakeParseException("error.snowflake.unsupported_statement", "WITH");
            }
            i++;
            if (i >= tokens.size() || !tokens.get(i).isSymbol("(")) {
                throw new SnowflakeParseException("error.snowflake.unsupported_statement", "WITH");
            }
            i = skipParens(tokens, i); // CTE body
            if (i < tokens.size() && tokens.get(i).isSymbol(",")) {
                i++;
                continue;
            }
            break;
        }
        if (i >= tokens.size()) {
            throw new SnowflakeParseException("error.snowflake.unsupported_statement", "WITH");
        }
        return i;
    }

    /** Index just past the paren group whose opening {@code (} sits at {@code open}. */
    private static int skipParens(List<Token> tokens, int open) {
        int depth = tokens.get(open).depth();
        for (int i = open + 1; i < tokens.size(); i++) {
            if (tokens.get(i).depth() == depth && tokens.get(i).isSymbol(")")) {
                return i + 1;
            }
        }
        throw new SnowflakeParseException("error.snowflake.unbalanced");
    }

    // ---- target refs ------------------------------------------------------------------------

    /**
     * The dotted ref after the first depth-matching {@code keyword}. With
     * {@code rejectFunctionCall}, a ref immediately followed by {@code (} (a table function like
     * {@code TABLE(GENERATOR(…))} or {@code my_func(x)}) yields no target; INTO targets keep it
     * off because {@code INSERT INTO t (cols)} is a column list, not a call.
     */
    private static SnowflakeTableRef refAfterWord(List<Token> tokens, int from, String keyword,
                                                  int depth, boolean rejectFunctionCall) {
        for (int i = from; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == depth && token.isWord(keyword)) {
                var ref = dottedRef(tokens, i + 1);
                if (ref != null && rejectFunctionCall) {
                    int end = i + 1 + (ref.segments().size() * 2 - 1);
                    if (end < tokens.size() && tokens.get(end).isSymbol("(")) {
                        return null;
                    }
                }
                return ref;
            }
        }
        return null;
    }

    private static SnowflakeTableRef truncateTarget(List<Token> tokens, int verbIndex) {
        int i = verbIndex + 1;
        if (i < tokens.size() && tokens.get(i).isWord("TABLE")) {
            i++;
        }
        i = skipIfExists(tokens, i);
        return dottedRef(tokens, i);
    }

    private SnowflakeTableRef ddlTarget(List<Token> tokens, int verbIndex, String verb) {
        int i = verbIndex + 1;
        if (verb.equals("CREATE") && i < tokens.size() && tokens.get(i).isWord("OR")) {
            if (i + 1 >= tokens.size() || !tokens.get(i + 1).isWord("REPLACE")) {
                throw new SnowflakeParseException("error.snowflake.unsupported_statement", verb);
            }
            i += 2;
        }
        while (i < tokens.size() && tokens.get(i).kind() == Kind.WORD
                && DDL_MODIFIERS.contains(tokens.get(i).value())) {
            i++;
        }
        if (i >= tokens.size() || tokens.get(i).kind() != Kind.WORD) {
            throw new SnowflakeParseException("error.snowflake.unsupported_statement", verb);
        }
        var object = tokens.get(i);
        if (!DDL_OBJECTS.contains(object.value())) {
            // PROCEDURE / FUNCTION / TASK / STREAM / PIPE / STAGE / WAREHOUSE / USER / ROLE /
            // INTEGRATION / SHARE — and any other object kind — fail closed.
            throw new SnowflakeParseException("error.snowflake.unsupported_statement",
                    verb + " " + object.text());
        }
        i = skipIfExists(tokens, i + 1);
        return dottedRef(tokens, i);
    }

    /** Skips an optional {@code IF EXISTS} / {@code IF NOT EXISTS} run starting at {@code i}. */
    private static int skipIfExists(List<Token> tokens, int i) {
        if (i < tokens.size() && tokens.get(i).isWord("IF")) {
            if (i + 2 < tokens.size() && tokens.get(i + 1).isWord("NOT")
                    && tokens.get(i + 2).isWord("EXISTS")) {
                return i + 3;
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).isWord("EXISTS")) {
                return i + 2;
            }
        }
        return i;
    }

    /** Parses a dotted {@code [db.][schema.]table} reference starting at {@code i}, else null. */
    private static SnowflakeTableRef dottedRef(List<Token> tokens, int i) {
        if (i >= tokens.size() || !isIdentifier(tokens.get(i))) {
            return null;
        }
        if (tokens.get(i).kind() == Kind.WORD && NON_TABLE_WORDS.contains(tokens.get(i).value())) {
            return null;
        }
        var segments = new ArrayList<String>();
        segments.add(tokens.get(i).identifier());
        int j = i + 1;
        while (j + 1 < tokens.size() && tokens.get(j).isSymbol(".")
                && isIdentifier(tokens.get(j + 1))) {
            segments.add(tokens.get(j + 1).identifier());
            j += 2;
        }
        return new SnowflakeTableRef(segments);
    }

    private static boolean isIdentifier(Token token) {
        return token.kind() == Kind.WORD || token.kind() == Kind.QUOTED_IDENT;
    }

    private static boolean hasWordAtDepth(List<Token> tokens, String word, int depth) {
        return tokens.stream().anyMatch(t -> t.depth() == depth && t.isWord(word));
    }

    // ---- referenced-table collection ----------------------------------------------------------

    /**
     * Collects every table referenced after a {@code FROM} / {@code JOIN} / {@code INTO} /
     * {@code USING} introducer that sits in <em>query context</em> — the top level or inside a
     * parenthesized subselect — so a {@code FROM} inside a scalar function call (e.g.
     * {@code EXTRACT(month FROM ts)}) is never mistaken for a table source. Function-style refs
     * ({@code TABLE(…)}, {@code LATERAL …}, {@code VALUES (…)}, {@code f(x)}) are skipped;
     * comma-separated FROM lists collect every element; CTE names are excluded.
     */
    private static Set<String> collectTables(List<Token> tokens, Set<String> cteNames,
                                             SnowflakeTableRef target) {
        var refs = new ArrayList<SnowflakeTableRef>();
        if (target != null) {
            refs.add(target);
        }
        var queryContext = new ArrayDeque<Boolean>();
        queryContext.push(Boolean.TRUE);
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.isSymbol("(")) {
                var next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                queryContext.push(next != null && (next.isWord("SELECT") || next.isWord("WITH")));
                continue;
            }
            if (token.isSymbol(")")) {
                if (queryContext.size() > 1) {
                    queryContext.pop();
                }
                continue;
            }
            if (!Boolean.TRUE.equals(queryContext.peek()) || token.kind() != Kind.WORD) {
                continue;
            }
            switch (token.value()) {
                case "FROM" -> collectRefList(tokens, i + 1, true, refs);
                case "JOIN", "USING" -> collectRefList(tokens, i + 1, false, refs);
                case "INTO" -> {
                    var ref = dottedRef(tokens, i + 1);
                    if (ref != null) {
                        refs.add(ref);
                    }
                }
                default -> {
                    // not an introducer
                }
            }
        }
        var tables = new LinkedHashSet<String>();
        for (var ref : refs) {
            if (ref.segments().size() == 1 && cteNames.contains(ref.lastSegment())) {
                continue;
            }
            tables.add(ref.normalized());
        }
        return tables;
    }

    /** Collects the (possibly comma-separated) table refs starting at {@code i}. */
    private static void collectRefList(List<Token> tokens, int i, boolean commaList,
                                       List<SnowflakeTableRef> refs) {
        while (i < tokens.size()) {
            var token = tokens.get(i);
            if (token.isSymbol("(") || token.isWord("LATERAL")) {
                return; // subselect / VALUES list / lateral flatten — no direct table ref here
            }
            if (!isIdentifier(token)) {
                return;
            }
            var ref = dottedRef(tokens, i);
            if (ref == null) {
                return;
            }
            int end = i + (ref.segments().size() * 2 - 1);
            if (end < tokens.size() && tokens.get(end).isSymbol("(")) {
                return; // function-style ref: TABLE(...), VALUES(...) parsed as words, f(x)
            }
            if (!token.isWord("TABLE") && !token.isWord("VALUES")) {
                refs.add(ref);
            }
            if (!commaList) {
                return;
            }
            // Skip an optional alias ([AS] ident), then continue on a comma.
            int j = end;
            if (j < tokens.size() && tokens.get(j).isWord("AS")) {
                j++;
            }
            if (j < tokens.size() && isIdentifier(tokens.get(j))
                    && !(tokens.get(j).kind() == Kind.WORD
                            && REF_LIST_STOP.contains(tokens.get(j).value()))) {
                j++;
            }
            if (j < tokens.size() && tokens.get(j).isSymbol(",")
                    && tokens.get(j).depth() == token.depth()) {
                i = j + 1;
                continue;
            }
            return;
        }
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
