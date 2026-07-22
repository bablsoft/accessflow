package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.engine.bigquery.BigQuerySqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.bigquery.BigQuerySqlTokenizer.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and classifies a submitted GoogleSQL statement without a full AST: a keyword classifier
 * over the {@link BigQuerySqlTokenizer} token stream, mirroring the JSqlParser path's guarantees.
 * Classification: {@code SELECT} (optionally behind a {@code WITH} prologue — the final top-level
 * verb decides) → SELECT; {@code INSERT} → INSERT; {@code UPDATE} → UPDATE; {@code DELETE} →
 * DELETE; {@code MERGE} → UPDATE; {@code TRUNCATE TABLE} and {@code CREATE}/{@code ALTER}/
 * {@code DROP} of TABLE / VIEW / MATERIALIZED VIEW / SCHEMA → DDL. Everything else fails closed
 * with {@link InvalidSqlException} (HTTP 422) — scripting and procedural verbs ({@code BEGIN},
 * {@code DECLARE}, {@code CALL}, {@code EXECUTE IMMEDIATE}, {@code LOOP}, …), admin verbs
 * ({@code GRANT}, {@code EXPORT}, {@code LOAD}, {@code ASSERT}, {@code SET}), routine/index/model
 * DDL, multi-statement input, and user-supplied {@code ?} / {@code @param} placeholders (the
 * positional binds are reserved for row security, and BigQuery forbids mixing parameter styles).
 * {@code referencedTables} carries lowercase dot-joined paths (CTE aliases excluded) for the
 * host's allow-list check.
 */
class BigQueryQueryParser {

    private static final Set<String> MAIN_VERBS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE",
            "MERGE");
    private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP");
    private static final Set<String> DDL_OBJECTS = Set.of("TABLE", "VIEW", "MATERIALIZED", "SCHEMA");
    /** Words that terminate a FROM item list / can never be a table alias. */
    private static final Set<String> FROM_STOP_WORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "ON", "USING", "JOIN", "INNER", "LEFT", "RIGHT",
            "FULL", "CROSS", "SET", "WHEN", "TABLESAMPLE", "PIVOT", "UNPIVOT", "FOR", "AS");
    /** Unquoted words that can never start a table reference (keyword guard for parseRef). */
    private static final Set<String> NON_TABLE_WORDS;

    static {
        var words = new java.util.HashSet<>(FROM_STOP_WORDS);
        words.addAll(Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "INTO", "VALUES",
                "TABLE", "VIEW", "SCHEMA", "MATERIALIZED", "IF", "NOT", "EXISTS", "WITH", "BY",
                "UNNEST", "AND", "OR"));
        NON_TABLE_WORDS = Set.copyOf(words);
    }

    private final EngineMessages messages;

    BigQueryQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result for the workflow layer (query type, tables, routing hints). */
    SqlParseResult parse(String query) {
        var statement = parseStatement(query);
        return new SqlParseResult(statement.kind().queryType(), false, List.of(query),
                statement.tables(), statement.hasWhere(), statement.hasLimit());
    }

    /** Full parse to the executable {@link BigQueryStatement}; reused by the executor. */
    BigQueryStatement parseStatement(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.bigquery.blank");
        }
        try {
            var tokens = singleStatement(BigQuerySqlTokenizer.tokenize(query));
            rejectPlaceholders(tokens);
            return classify(query, tokens);
        } catch (BigQueryParseException ex) {
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
            throw new BigQueryParseException("error.bigquery.blank");
        }
        if (statements.size() > 1) {
            throw new BigQueryParseException("error.bigquery.multiple_statements");
        }
        return statements.get(0);
    }

    /** Positional binds are reserved for row security; named params cannot mix with them. */
    private static void rejectPlaceholders(List<Token> tokens) {
        for (var token : tokens) {
            if (token.isSymbol("?") || token.isSymbol("@")) {
                throw new BigQueryParseException("error.bigquery.placeholders_forbidden");
            }
        }
    }

    // ---- classification ---------------------------------------------------------------------

    private BigQueryStatement classify(String sql, List<Token> tokens) {
        var first = tokens.get(0);
        if (first.kind() != Kind.WORD) {
            throw new BigQueryParseException("error.bigquery.unsupported_statement", first.text());
        }
        var cteNames = new HashSet<String>();
        boolean hasCte = first.isWord("WITH");
        int verbIndex = hasCte ? mainVerbAfterCtes(tokens, cteNames) : 0;
        var verb = tokens.get(verbIndex);
        if (verb.kind() != Kind.WORD) {
            throw new BigQueryParseException("error.bigquery.unsupported_statement", verb.text());
        }

        BigQueryStatementKind kind;
        BigQueryTableRef target;
        if (MAIN_VERBS.contains(verb.value())) {
            kind = switch (verb.value()) {
                case "SELECT" -> BigQueryStatementKind.SELECT;
                case "INSERT" -> BigQueryStatementKind.INSERT;
                case "UPDATE" -> BigQueryStatementKind.UPDATE;
                case "DELETE" -> BigQueryStatementKind.DELETE;
                default -> BigQueryStatementKind.MERGE;
            };
            target = dmlTarget(tokens, verbIndex, kind, cteNames);
        } else if (verb.isWord("TRUNCATE")) {
            if (verbIndex + 1 >= tokens.size() || !tokens.get(verbIndex + 1).isWord("TABLE")) {
                throw new BigQueryParseException("error.bigquery.unsupported_statement", verb.text());
            }
            kind = BigQueryStatementKind.DDL;
            target = requiredRef(tokens, verbIndex + 2);
        } else if (DDL_VERBS.contains(verb.value())) {
            kind = BigQueryStatementKind.DDL;
            target = ddlTarget(tokens, verbIndex);
        } else {
            throw new BigQueryParseException("error.bigquery.unsupported_statement", verb.text());
        }

        var tables = new LinkedHashSet<String>();
        if (target != null) {
            tables.add(target.normalized());
        }
        boolean hasCommaJoin = collectFromAndJoinRefs(tokens, tables, cteNames);
        boolean hasWhere = hasTopLevelWord(tokens, "WHERE");
        boolean hasLimit = hasTopLevelWord(tokens, "LIMIT");
        return new BigQueryStatement(sql, kind, target, Set.copyOf(tables), hasWhere, hasLimit,
                hasCte, hasCommaJoin, List.copyOf(tokens));
    }

    /**
     * Skips the {@code WITH name [(cols)] AS (…) [, …]} prologue: CTE bodies sit at depth &gt; 0,
     * so the first depth-0 main verb is the statement's verb. CTE names are collected so FROM
     * references to them are not reported as tables.
     */
    private static int mainVerbAfterCtes(List<Token> tokens, Set<String> cteNames) {
        for (int i = 1; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() != 0 || token.kind() != Kind.WORD) {
                continue;
            }
            if (MAIN_VERBS.contains(token.value())) {
                return i;
            }
            if (token.value().equals("AS")) {
                var name = cteNameBefore(tokens, i);
                if (name != null) {
                    cteNames.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        throw new BigQueryParseException("error.bigquery.unsupported_statement", tokens.get(0).text());
    }

    /** The CTE name preceding an {@code AS}, skipping an optional {@code (col, …)} list. */
    private static String cteNameBefore(List<Token> tokens, int asIndex) {
        int i = asIndex - 1;
        if (i >= 0 && tokens.get(i).isSymbol(")")) {
            while (i >= 0 && !(tokens.get(i).isSymbol("(") && tokens.get(i).depth() == 0)) {
                i--;
            }
            i--;
        }
        return i >= 0 && isIdentifier(tokens.get(i)) ? tokens.get(i).identifier() : null;
    }

    private static BigQueryTableRef dmlTarget(List<Token> tokens, int verbIndex,
                                              BigQueryStatementKind kind, Set<String> cteNames) {
        return switch (kind) {
            case SELECT -> firstFromRef(tokens, cteNames);
            case INSERT, MERGE -> requiredRef(tokens, skipWord(tokens, verbIndex + 1, "INTO"));
            case UPDATE -> requiredRef(tokens, verbIndex + 1);
            case DELETE -> requiredRef(tokens, skipWord(tokens, verbIndex + 1, "FROM"));
            case DDL -> null;
        };
    }

    private static int skipWord(List<Token> tokens, int i, String word) {
        return i < tokens.size() && tokens.get(i).isWord(word) ? i + 1 : i;
    }

    /**
     * Classifies a {@code CREATE}/{@code ALTER}/{@code DROP}: only TABLE / VIEW /
     * MATERIALIZED VIEW / SCHEMA objects are supported; routine, index, model, reservation, and
     * row-access-policy DDL fails closed. {@code CREATE TABLE FUNCTION} (a TVF) is told apart from
     * {@code CREATE TABLE} by the token after the object word.
     */
    private static BigQueryTableRef ddlTarget(List<Token> tokens, int verbIndex) {
        var verb = tokens.get(verbIndex).value();
        int i = verbIndex + 1;
        if (verb.equals("CREATE") && i + 1 < tokens.size() && tokens.get(i).isWord("OR")
                && tokens.get(i + 1).isWord("REPLACE")) {
            i += 2;
        }
        if (i >= tokens.size() || tokens.get(i).kind() != Kind.WORD
                || !DDL_OBJECTS.contains(tokens.get(i).value())) {
            throw new BigQueryParseException("error.bigquery.unsupported_statement",
                    verb + (i < tokens.size() ? " " + tokens.get(i).text() : ""));
        }
        var object = tokens.get(i).value();
        if (object.equals("MATERIALIZED")) {
            if (i + 1 >= tokens.size() || !tokens.get(i + 1).isWord("VIEW")) {
                throw new BigQueryParseException("error.bigquery.unsupported_statement",
                        verb + " MATERIALIZED");
            }
            i++;
        }
        if (object.equals("TABLE") && i + 1 < tokens.size() && tokens.get(i + 1).isWord("FUNCTION")) {
            throw new BigQueryParseException("error.bigquery.unsupported_statement",
                    verb + " TABLE FUNCTION");
        }
        i++;
        if (i + 1 < tokens.size() && tokens.get(i).isWord("IF")) {
            i += tokens.get(i + 1).isWord("NOT") ? 3 : 2;
        }
        return requiredRef(tokens, i);
    }

    // ---- table-ref collection ------------------------------------------------------------------

    /**
     * Collects every {@code FROM} / {@code JOIN} / {@code USING} table reference at any depth
     * (subqueries included — the allow-list must see them), skipping subselects, {@code UNNEST}
     * calls, aliases, and CTE names. Returns whether a top-level FROM lists more than one item
     * (comma-join), which the row-security applier fails closed on.
     */
    private static boolean collectFromAndJoinRefs(List<Token> tokens, Set<String> tables,
                                                  Set<String> cteNames) {
        boolean commaJoin = false;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.kind() != Kind.WORD) {
                continue;
            }
            switch (token.value()) {
                case "FROM" -> commaJoin |= collectFromList(tokens, i, tables, cteNames);
                case "JOIN", "USING" -> collectRef(tokens, i + 1, tables, cteNames);
                default -> { /* not a table introducer */ }
            }
        }
        return commaJoin;
    }

    /** Walks the comma-separated FROM item list at the FROM token's depth. */
    private static boolean collectFromList(List<Token> tokens, int fromIndex, Set<String> tables,
                                           Set<String> cteNames) {
        int depth = tokens.get(fromIndex).depth();
        boolean commaJoin = false;
        int i = fromIndex + 1;
        while (i < tokens.size()) {
            i = afterFromItem(tokens, i, tables, cteNames);
            // Skip an optional alias ([AS] ident) after the item.
            if (i < tokens.size() && tokens.get(i).isWord("AS")) {
                i++;
            }
            if (i < tokens.size() && isIdentifier(tokens.get(i))
                    && !FROM_STOP_WORDS.contains(tokens.get(i).value())) {
                i++;
            }
            if (i < tokens.size() && tokens.get(i).depth() == depth && tokens.get(i).isSymbol(",")) {
                commaJoin = true;
                i++;
                continue;
            }
            break;
        }
        return commaJoin;
    }

    /** Consumes one FROM item (table ref, subselect, or UNNEST call), collecting table refs. */
    private static int afterFromItem(List<Token> tokens, int i, Set<String> tables,
                                     Set<String> cteNames) {
        if (i >= tokens.size()) {
            return i;
        }
        var token = tokens.get(i);
        if (token.isSymbol("(")) {
            return afterGroup(tokens, i);
        }
        if (token.isWord("UNNEST") && i + 1 < tokens.size() && tokens.get(i + 1).isSymbol("(")) {
            return afterGroup(tokens, i + 1);
        }
        var ref = parseRef(tokens, i);
        if (ref == null) {
            return i;
        }
        if (!cteNames.contains(ref.ref().normalized())) {
            tables.add(ref.ref().normalized());
        }
        return ref.next();
    }

    /** Index just past the group opened by the {@code (} at {@code openIndex}. */
    private static int afterGroup(List<Token> tokens, int openIndex) {
        int openDepth = tokens.get(openIndex).depth();
        for (int i = openIndex + 1; i < tokens.size(); i++) {
            if (tokens.get(i).depth() == openDepth && tokens.get(i).isSymbol(")")) {
                return i + 1;
            }
        }
        return tokens.size();
    }

    private static void collectRef(List<Token> tokens, int i, Set<String> tables,
                                   Set<String> cteNames) {
        if (i < tokens.size() && (tokens.get(i).isSymbol("(") || tokens.get(i).isWord("UNNEST"))) {
            return;
        }
        var ref = parseRef(tokens, i);
        if (ref != null && !cteNames.contains(ref.ref().normalized())) {
            tables.add(ref.ref().normalized());
        }
    }

    private static BigQueryTableRef firstFromRef(List<Token> tokens, Set<String> cteNames) {
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.isWord("FROM")) {
                var collected = new LinkedHashSet<String>();
                afterFromItem(tokens, i + 1, collected, cteNames);
                return collected.isEmpty() ? null
                        : new BigQueryTableRef(collected.iterator().next());
            }
        }
        return null;
    }

    private static BigQueryTableRef requiredRef(List<Token> tokens, int i) {
        var ref = parseRef(tokens, i);
        if (ref == null) {
            throw new BigQueryParseException("error.bigquery.table_required");
        }
        return ref.ref();
    }

    private record RefParse(BigQueryTableRef ref, int next) {
    }

    /**
     * Parses a {@code segment[.segment…]} reference starting at {@code i}; a backtick-quoted
     * segment may itself contain the whole dotted path. Returns {@code null} when no identifier
     * starts there.
     */
    private static RefParse parseRef(List<Token> tokens, int i) {
        if (i >= tokens.size() || !isIdentifier(tokens.get(i))
                || (tokens.get(i).kind() == Kind.WORD
                        && NON_TABLE_WORDS.contains(tokens.get(i).value()))) {
            return null;
        }
        var path = new StringBuilder(tokens.get(i).identifier());
        int next = i + 1;
        while (next + 1 < tokens.size() && tokens.get(next).isSymbol(".")
                && isIdentifier(tokens.get(next + 1))) {
            path.append('.').append(tokens.get(next + 1).identifier());
            next += 2;
        }
        return new RefParse(new BigQueryTableRef(path.toString().toLowerCase(Locale.ROOT)), next);
    }

    private static boolean isIdentifier(Token token) {
        return token.kind() == Kind.WORD || token.kind() == Kind.QUOTED_IDENT;
    }

    private static boolean hasTopLevelWord(List<Token> tokens, String word) {
        return tokens.stream().anyMatch(t -> t.depth() == 0 && t.isWord(word));
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
