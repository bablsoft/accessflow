package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.engine.databricks.DatabricksSqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.databricks.DatabricksSqlTokenizer.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and classifies a submitted Databricks SQL statement without a full AST: a keyword
 * classifier over the {@link DatabricksSqlTokenizer} token stream, mirroring the JSqlParser path's
 * guarantees (the Couchbase / Cassandra pattern — Databricks SQL is SQL-shaped). Exactly one
 * statement per submission. A leading {@code WITH} classifies by the final top-level verb.
 * Allowed: {@code SELECT} → SELECT; {@code INSERT} (INTO / OVERWRITE) → INSERT; {@code UPDATE} →
 * UPDATE; {@code DELETE} → DELETE; {@code MERGE} → UPDATE; {@code TRUNCATE} and
 * {@code CREATE}/{@code ALTER}/{@code DROP} of tables / views / schemas / databases / materialized
 * views → DDL. Session/state, ingestion, maintenance, and governance verbs — and DDL over
 * functions, volumes, shares, catalogs, credentials, principals, etc. — are rejected with
 * {@link InvalidSqlException} (HTTP 422), as are {@code ?} positional and {@code :name} named
 * parameter markers in the user SQL (the engine reserves named parameters for row security);
 * everything off-list fails closed with the same key. {@code referencedTables} carries the
 * normalized (backticks stripped, lowercased, dot-joined) refs after FROM / JOIN / INTO /
 * OVERWRITE / UPDATE / MERGE USING / TRUNCATE / the DDL object name, descending into subselects
 * while skipping CTE names, aliases, {@code VALUES}, and {@code LATERAL VIEW} generators.
 */
class DatabricksQueryParser {

    private static final Set<String> REJECTED_VERBS = Set.of(
            "USE", "SET", "RESET", "CACHE", "UNCACHE", "COPY", "CALL", "GRANT", "REVOKE", "MSCK",
            "ANALYZE", "OPTIMIZE", "VACUUM", "REFRESH", "DECLARE", "BEGIN", "EXECUTE", "DESCRIBE",
            "DESC", "SHOW", "EXPLAIN", "LIST", "CLEAN", "REPAIR", "RESTORE", "CONVERT", "FSCK");

    private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE");

    private static final Set<String> DDL_ALLOWED_OBJECTS = Set.of(
            "TABLE", "VIEW", "SCHEMA", "DATABASE");

    private static final Set<String> DDL_REJECTED_OBJECTS = Set.of(
            "FUNCTION", "VOLUME", "SHARE", "RECIPIENT", "PROVIDER", "CATALOG", "CONNECTION",
            "CREDENTIAL", "LOCATION", "POLICY", "USER", "GROUP", "SERVICE");

    /** Words that can never start a table reference in a table position. */
    private static final Set<String> NON_TABLE_WORDS = Set.of(
            "VALUES", "LATERAL", "SELECT", "UNNEST", "WITH", "SET", "WHERE");

    /** Depth-0 words that end a FROM comma-list (clause / join / set-op boundaries). */
    private static final Set<String> FROM_BOUNDARY = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "ORDER", "SORT", "CLUSTER", "DISTRIBUTE",
            "LIMIT", "OFFSET", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "ANTI", "SEMI",
            "NATURAL", "LATERAL", "ON", "USING", "UNION", "INTERSECT", "EXCEPT", "MINUS", "SELECT",
            "SET", "WHEN", "VALUES", "WINDOW", "PIVOT", "UNPIVOT", "TABLESAMPLE", "RETURNING");

    private final EngineMessages messages;

    DatabricksQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result for the workflow layer (query type, tables, routing hints). */
    SqlParseResult parse(String query) {
        var statement = parseStatement(query);
        return new SqlParseResult(statement.kind().queryType(), false, List.of(query),
                statement.tables(), statement.hasWhere(), statement.hasLimit());
    }

    /** Full parse to the executable {@link DatabricksStatement}; reused by the executor. */
    DatabricksStatement parseStatement(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.databricks.blank");
        }
        try {
            var allTokens = DatabricksSqlTokenizer.tokenize(query);
            var tokens = singleStatement(allTokens);
            rejectParameterMarkers(tokens);
            return classify(query, tokens);
        } catch (DatabricksParseException ex) {
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
            throw new DatabricksParseException("error.databricks.blank");
        }
        if (statements.size() > 1) {
            throw new DatabricksParseException("error.databricks.multiple_statements");
        }
        return statements.get(0);
    }

    private static void rejectParameterMarkers(List<Token> tokens) {
        for (var token : tokens) {
            if (token.kind() == Kind.NAMED_PARAM || token.isSymbol("?")) {
                throw new DatabricksParseException("error.databricks.parameter_marker_forbidden");
            }
        }
    }

    // ---- classification ---------------------------------------------------------------------

    private DatabricksStatement classify(String sql, List<Token> tokens) {
        var first = tokens.get(0);
        if (first.kind() != Kind.WORD) {
            throw new DatabricksParseException("error.databricks.unsupported_statement", first.text());
        }
        var cteNames = new LinkedHashSet<String>();
        int verbIndex = 0;
        boolean hasCte = false;
        if (first.isWord("WITH")) {
            hasCte = true;
            verbIndex = walkCte(tokens, cteNames);
        }
        var verb = tokens.get(verbIndex);
        if (verb.kind() != Kind.WORD) {
            throw new DatabricksParseException("error.databricks.unsupported_statement", verb.text());
        }
        if (REJECTED_VERBS.contains(verb.value())) {
            throw new DatabricksParseException("error.databricks.unsupported_statement", verb.text());
        }
        var tables = new LinkedHashSet<String>();
        DatabricksStatementKind kind = switch (verb.value()) {
            case "SELECT" -> DatabricksStatementKind.SELECT;
            case "INSERT" -> DatabricksStatementKind.INSERT;
            case "UPDATE" -> DatabricksStatementKind.UPDATE;
            case "DELETE" -> DatabricksStatementKind.DELETE;
            case "MERGE" -> DatabricksStatementKind.MERGE;
            case "CREATE", "ALTER", "DROP", "TRUNCATE" -> DatabricksStatementKind.DDL;
            default -> throw new DatabricksParseException(
                    "error.databricks.unsupported_statement", verb.text());
        };
        if (hasCte && (DDL_VERBS.contains(verb.value()))) {
            throw new DatabricksParseException("error.databricks.unsupported_statement", verb.text());
        }
        collectTarget(tokens, verbIndex, kind, tables);
        collectReferencedTables(tokens, kind, cteNames, tables);
        return new DatabricksStatement(sql, kind, Set.copyOf(tables),
                hasTopLevelWord(tokens, "WHERE"), hasTopLevelWord(tokens, "LIMIT"), hasCte,
                List.copyOf(tokens));
    }

    /** Walks the {@code WITH name [(cols)] AS ( … ) [, …]} prefix; returns the main-verb index. */
    private static int walkCte(List<Token> tokens, Set<String> cteNames) {
        int i = 1;
        if (i < tokens.size() && tokens.get(i).isWord("RECURSIVE")) {
            i++;
        }
        while (true) {
            if (i >= tokens.size() || !isIdentifier(tokens.get(i))) {
                throw new DatabricksParseException("error.databricks.unsupported_statement", "WITH");
            }
            cteNames.add(tokens.get(i).identifier().toLowerCase(Locale.ROOT));
            i++;
            if (i < tokens.size() && tokens.get(i).isSymbol("(")) {
                i = closeIndex(tokens, i) + 1;
            }
            if (i >= tokens.size() || !tokens.get(i).isWord("AS")) {
                throw new DatabricksParseException("error.databricks.unsupported_statement", "WITH");
            }
            i++;
            if (i >= tokens.size() || !tokens.get(i).isSymbol("(")) {
                throw new DatabricksParseException("error.databricks.unsupported_statement", "WITH");
            }
            i = closeIndex(tokens, i) + 1;
            if (i < tokens.size() && tokens.get(i).isSymbol(",")) {
                i++;
                continue;
            }
            break;
        }
        if (i >= tokens.size()) {
            throw new DatabricksParseException("error.databricks.unsupported_statement", "WITH");
        }
        return i;
    }

    /** Index of the {@code )} closing the {@code (} at {@code openIndex} (same recorded depth). */
    private static int closeIndex(List<Token> tokens, int openIndex) {
        int openDepth = tokens.get(openIndex).depth();
        for (int j = openIndex + 1; j < tokens.size(); j++) {
            var token = tokens.get(j);
            if (token.isSymbol(")") && token.depth() == openDepth) {
                return j;
            }
        }
        throw new DatabricksParseException("error.databricks.unbalanced");
    }

    // ---- target requirements per statement kind ----------------------------------------------

    private static void collectTarget(List<Token> tokens, int verbIndex,
                                      DatabricksStatementKind kind, Set<String> tables) {
        switch (kind) {
            case INSERT -> tables.add(requireRef(insertTargetIndex(tokens, verbIndex), tokens));
            case UPDATE -> tables.add(requireRef(verbIndex + 1, tokens));
            case DELETE -> tables.add(requireRef(
                    afterKeyword(tokens, verbIndex, "FROM"), tokens));
            case MERGE -> tables.add(requireRef(
                    afterKeyword(tokens, verbIndex, "INTO"), tokens));
            case DDL -> tables.add(requireRef(ddlObjectIndex(tokens, verbIndex), tokens));
            case SELECT -> { /* SELECT 1 is legal — no required target */ }
        }
    }

    private static int insertTargetIndex(List<Token> tokens, int verbIndex) {
        int i = verbIndex + 1;
        if (i >= tokens.size()
                || !(tokens.get(i).isWord("INTO") || tokens.get(i).isWord("OVERWRITE"))) {
            throw new DatabricksParseException("error.databricks.table_required");
        }
        i++;
        if (i < tokens.size() && tokens.get(i).isWord("TABLE")) {
            i++;
        }
        return i;
    }

    private static int afterKeyword(List<Token> tokens, int from, String keyword) {
        int depth = tokens.get(from).depth();
        for (int i = from + 1; i < tokens.size(); i++) {
            if (tokens.get(i).depth() == depth && tokens.get(i).isWord(keyword)) {
                return i + 1;
            }
        }
        throw new DatabricksParseException("error.databricks.table_required");
    }

    private static int ddlObjectIndex(List<Token> tokens, int verbIndex) {
        var verb = tokens.get(verbIndex);
        int i = verbIndex + 1;
        if (verb.isWord("TRUNCATE")) {
            if (i < tokens.size() && tokens.get(i).isWord("TABLE")) {
                i++;
            }
            return i;
        }
        if (verb.isWord("CREATE") && i + 1 < tokens.size()
                && tokens.get(i).isWord("OR") && tokens.get(i + 1).isWord("REPLACE")) {
            i += 2;
        }
        if (i >= tokens.size() || tokens.get(i).kind() != Kind.WORD) {
            throw new DatabricksParseException(
                    "error.databricks.unsupported_statement", verb.text());
        }
        var object = tokens.get(i);
        if (object.isWord("MATERIALIZED")) {
            if (i + 1 >= tokens.size() || !tokens.get(i + 1).isWord("VIEW")) {
                throw new DatabricksParseException(
                        "error.databricks.unsupported_statement", object.text());
            }
            i += 2;
        } else if (DDL_REJECTED_OBJECTS.contains(object.value())) {
            throw new DatabricksParseException("error.databricks.unsupported_statement",
                    verb.text() + " " + object.text());
        } else if (DDL_ALLOWED_OBJECTS.contains(object.value())) {
            i++;
        } else {
            // Off-list DDL object (EXTERNAL, TEMPORARY, STREAMING, LIVE, INDEX, …) — fail closed.
            throw new DatabricksParseException(
                    "error.databricks.unsupported_statement", object.text());
        }
        // Skip IF [NOT] EXISTS.
        if (i < tokens.size() && tokens.get(i).isWord("IF")) {
            if (i + 2 < tokens.size() && tokens.get(i + 1).isWord("NOT")
                    && tokens.get(i + 2).isWord("EXISTS")) {
                i += 3;
            } else if (i + 1 < tokens.size() && tokens.get(i + 1).isWord("EXISTS")) {
                i += 2;
            }
        }
        return i;
    }

    private static String requireRef(int index, List<Token> tokens) {
        var ref = parseRef(tokens, index);
        if (ref == null) {
            throw new DatabricksParseException("error.databricks.table_required");
        }
        return ref.ref().normalized();
    }

    // ---- referenced-table collection ----------------------------------------------------------

    /**
     * Scans the token stream for table references after FROM / JOIN / (MERGE) USING, descending
     * only into paren groups that open a query scope ({@code (SELECT …} / {@code (WITH …}) so a
     * {@code FROM} inside function arguments (e.g. {@code extract(day FROM ts)}) is never
     * mistaken for a clause.
     */
    private static void collectReferencedTables(List<Token> tokens, DatabricksStatementKind kind,
                                                Set<String> cteNames, Set<String> tables) {
        var queryScope = new ArrayDeque<Boolean>();
        queryScope.push(Boolean.TRUE);
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.isSymbol("(") || token.isSymbol("[") || token.isSymbol("{")) {
                boolean opensQuery = token.isSymbol("(") && i + 1 < tokens.size()
                        && (tokens.get(i + 1).isWord("SELECT") || tokens.get(i + 1).isWord("WITH"));
                queryScope.push(opensQuery);
                continue;
            }
            if (token.isSymbol(")") || token.isSymbol("]") || token.isSymbol("}")) {
                if (queryScope.size() > 1) {
                    queryScope.pop();
                }
                continue;
            }
            if (!Boolean.TRUE.equals(queryScope.peek()) || token.kind() != Kind.WORD) {
                continue;
            }
            if (token.isWord("FROM")) {
                collectFromList(tokens, i + 1, token.depth(), cteNames, tables);
            } else if (token.isWord("JOIN")
                    || (kind == DatabricksStatementKind.MERGE && token.isWord("USING"))) {
                addRef(tokens, i + 1, cteNames, tables);
            }
        }
    }

    /** Collects the comma-separated table refs of one FROM clause (aliases skipped). */
    private static void collectFromList(List<Token> tokens, int from, int depth,
                                        Set<String> cteNames, Set<String> tables) {
        int i = from;
        while (i < tokens.size()) {
            var token = tokens.get(i);
            if (token.depth() < depth) {
                return;
            }
            if (token.isSymbol("(") && token.depth() == depth) {
                i = closeIndex(tokens, i) + 1; // subselect / parenthesized source — inner scope scanned separately
            } else if (token.depth() == depth && isIdentifier(token)) {
                var parsed = parseRef(tokens, i);
                if (parsed != null) {
                    addParsed(parsed.ref(), cteNames, tables);
                    i = parsed.next();
                } else {
                    i++;
                }
            } else {
                i++;
            }
            // Skim aliases/modifiers until the next comma (another source) or a clause boundary.
            while (i < tokens.size()) {
                var t = tokens.get(i);
                if (t.depth() < depth) {
                    return;
                }
                if (t.depth() > depth) {
                    i++;
                    continue;
                }
                if (t.isSymbol(",")) {
                    i++;
                    break;
                }
                if (t.kind() == Kind.WORD && FROM_BOUNDARY.contains(t.value())) {
                    return;
                }
                i++;
            }
            if (i >= tokens.size()) {
                return;
            }
        }
    }

    private static void addRef(List<Token> tokens, int index, Set<String> cteNames,
                               Set<String> tables) {
        if (index < tokens.size() && tokens.get(index).isSymbol("(")) {
            return; // subselect — its contents are scanned by the scope walker
        }
        var parsed = parseRef(tokens, index);
        if (parsed != null) {
            addParsed(parsed.ref(), cteNames, tables);
        }
    }

    private static void addParsed(DatabricksTableRef ref, Set<String> cteNames,
                                  Set<String> tables) {
        if (ref.segments().size() == 1 && cteNames.contains(ref.lastSegment())) {
            return;
        }
        tables.add(ref.normalized());
    }

    // ---- ref parsing ---------------------------------------------------------------------------

    private record ParsedRef(DatabricksTableRef ref, int next) {
    }

    /** Parses a dotted {@code [catalog.][schema.]table} reference starting at {@code index}. */
    private static ParsedRef parseRef(List<Token> tokens, int index) {
        if (index >= tokens.size() || !isIdentifier(tokens.get(index))) {
            return null;
        }
        var first = tokens.get(index);
        if (first.kind() == Kind.WORD && NON_TABLE_WORDS.contains(first.value())) {
            return null;
        }
        var segments = new ArrayList<String>();
        segments.add(first.identifier());
        int i = index + 1;
        while (i + 1 < tokens.size() && tokens.get(i).isSymbol(".")
                && isIdentifier(tokens.get(i + 1))) {
            segments.add(tokens.get(i + 1).identifier());
            i += 2;
        }
        return new ParsedRef(new DatabricksTableRef(segments), i);
    }

    private static boolean isIdentifier(Token token) {
        return token.kind() == Kind.WORD || token.kind() == Kind.BACKTICK_IDENT;
    }

    private static boolean hasTopLevelWord(List<Token> tokens, String word) {
        return tokens.stream().anyMatch(t -> t.depth() == 0 && t.isWord(word));
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
