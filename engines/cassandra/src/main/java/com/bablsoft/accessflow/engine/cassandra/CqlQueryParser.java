package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.engine.cassandra.CqlTokenizer.Kind;
import com.bablsoft.accessflow.engine.cassandra.CqlTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses and classifies a submitted CQL statement without a full AST: a keyword classifier over the
 * {@link CqlTokenizer} token stream, mirroring the JSqlParser path's guarantees. Exactly one
 * statement is allowed (a trailing {@code ;} is tolerated). Classification: {@code SELECT} → SELECT;
 * {@code INSERT} → INSERT; {@code UPDATE} → UPDATE; {@code DELETE} → DELETE;
 * {@code CREATE}/{@code ALTER}/{@code DROP} of a {@code TABLE} / {@code KEYSPACE} / {@code INDEX} /
 * {@code TYPE} / {@code MATERIALIZED VIEW} and {@code TRUNCATE} → DDL. Lightweight transactions
 * ({@code IF NOT EXISTS} / {@code IF …}) ride on their base DML type. Everything else fails closed
 * with {@link InvalidSqlException} (HTTP 422) — including {@code BEGIN … BATCH} (the multi-statement
 * counterpart of the SQL engine's batch ban) and {@code CREATE}/{@code DROP FUNCTION}/{@code AGGREGATE}
 * (server-side code, the CQL counterpart of the MongoDB engine's {@code $where} ban).
 * {@code referencedTables} carries every referenced table (lowercased {@code table} or
 * {@code keyspace.table}) for the host's allow-list check.
 */
class CqlQueryParser {

    private static final Set<String> DML_VERBS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");
    private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP");
    /** DDL object keywords the engine accepts (TRIGGER / ROLE / USER / FUNCTION / AGGREGATE rejected). */
    private static final Set<String> DDL_OBJECTS = Set.of(
            "TABLE", "COLUMNFAMILY", "KEYSPACE", "INDEX", "TYPE", "VIEW");
    /** Keywords introducing a table reference for the allow-list scan. */
    private static final Set<String> REF_INTRODUCERS = Set.of("FROM", "INTO", "ON");

    private final EngineMessages messages;

    CqlQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result for the workflow layer (query type, tables, routing hints). */
    SqlParseResult parse(String query) {
        var statement = parseStatement(query);
        boolean hasLimit = statement.kind().isRead() && hasTopLevelWord(statement.tokens(), "LIMIT");
        return new SqlParseResult(statement.kind().queryType(), false, List.of(query),
                statement.tables(), statement.hasWhere(), hasLimit);
    }

    /** Full parse to the executable {@link CqlStatement}; reused by the executor. */
    CqlStatement parseStatement(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.cassandra.blank");
        }
        try {
            var allTokens = CqlTokenizer.tokenize(query);
            // BEGIN … BATCH carries inner ';' separators, so it must be rejected before the
            // statement splitter would otherwise mistake it for multiple statements.
            rejectBatch(allTokens);
            var tokens = singleStatement(allTokens);
            rejectForbidden(tokens);
            return classify(query, tokens);
        } catch (CqlParseException ex) {
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
            throw new CqlParseException("error.cassandra.blank");
        }
        if (statements.size() > 1) {
            throw new CqlParseException("error.cassandra.multiple_statements");
        }
        return statements.get(0);
    }

    // ---- forbidden constructs ---------------------------------------------------------------

    /** {@code BEGIN [UNLOGGED|COUNTER] BATCH … APPLY BATCH} — the CQL multi-statement carrier. */
    private static void rejectBatch(List<Token> tokens) {
        if (!tokens.isEmpty() && tokens.get(0).isWord("BEGIN")) {
            throw new CqlParseException("error.cassandra.batch_forbidden");
        }
    }

    private static void rejectForbidden(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (isUdfStatementHead(tokens, i)) {
                throw new CqlParseException("error.cassandra.udf_forbidden");
            }
        }
    }

    /** {@code CREATE [OR REPLACE] FUNCTION|AGGREGATE}, {@code DROP FUNCTION|AGGREGATE}. */
    private static boolean isUdfStatementHead(List<Token> tokens, int i) {
        var token = tokens.get(i);
        if (!token.isWord("CREATE") && !token.isWord("DROP")) {
            return false;
        }
        var next = wordAt(tokens, i + 1);
        if ("FUNCTION".equals(next) || "AGGREGATE".equals(next)) {
            return true;
        }
        return token.isWord("CREATE") && "OR".equals(next) && "REPLACE".equals(wordAt(tokens, i + 2))
                && ("FUNCTION".equals(wordAt(tokens, i + 3)) || "AGGREGATE".equals(wordAt(tokens, i + 3)));
    }

    // ---- classification ---------------------------------------------------------------------

    private CqlStatement classify(String sql, List<Token> tokens) {
        var first = tokens.get(0);
        if (first.kind() != Kind.WORD) {
            throw new CqlParseException("error.cassandra.unsupported_statement", first.text());
        }
        if (DML_VERBS.contains(first.value())) {
            return analyseDml(sql, tokens, kindOf(first.value()));
        }
        if (first.isWord("TRUNCATE") || (DDL_VERBS.contains(first.value()) && isAcceptedDdl(tokens))) {
            return analyseDdl(sql, tokens);
        }
        throw new CqlParseException("error.cassandra.unsupported_statement", first.text());
    }

    private static boolean isAcceptedDdl(List<Token> tokens) {
        var object = wordAt(tokens, 1);
        if ("CUSTOM".equals(object) || "MATERIALIZED".equals(object)) {
            object = wordAt(tokens, 2);
        }
        return object != null && DDL_OBJECTS.contains(object);
    }

    private static CqlStatementKind kindOf(String verb) {
        return switch (verb) {
            case "SELECT" -> CqlStatementKind.SELECT;
            case "INSERT" -> CqlStatementKind.INSERT;
            case "UPDATE" -> CqlStatementKind.UPDATE;
            case "DELETE" -> CqlStatementKind.DELETE;
            default -> throw new CqlParseException("error.cassandra.unsupported_statement", verb);
        };
    }

    // ---- structural analysis ----------------------------------------------------------------

    private CqlStatement analyseDml(String sql, List<Token> tokens, CqlStatementKind kind) {
        var tables = new LinkedHashSet<String>();
        CqlTableRef target = switch (kind) {
            case SELECT, DELETE -> refAfter(tokens, "FROM");
            case INSERT -> refAfter(tokens, "INTO");
            case UPDATE -> {
                var parsed = parseRef(tokens, 1);
                yield parsed == null ? null : parsed.ref();
            }
            default -> null;
        };
        if (target == null) {
            throw new CqlParseException("error.cassandra.keyspace_required");
        }
        tables.add(target.normalized());
        boolean hasWhere = collectRefsAndWhere(tokens, tables);
        return new CqlStatement(sql, kind, target, Set.copyOf(tables), hasWhere, List.copyOf(tokens));
    }

    private CqlStatement analyseDdl(String sql, List<Token> tokens) {
        var tables = new LinkedHashSet<String>();
        if (tokens.get(0).isWord("TRUNCATE")) {
            var parsed = parseRefSkipping(tokens, 1, Set.of("TABLE"));
            if (parsed != null) {
                tables.add(parsed.ref().normalized());
            }
        } else {
            var parsed = ddlObjectRef(tokens);
            if (parsed != null) {
                tables.add(parsed.ref().normalized());
            }
        }
        collectRefsAndWhere(tokens, tables);
        return new CqlStatement(sql, CqlStatementKind.DDL, null, Set.copyOf(tables), false,
                List.copyOf(tokens));
    }

    /** The table a {@code CREATE/ALTER/DROP TABLE|TYPE|…} (or {@code … INDEX … ON}) targets. */
    private static ParsedRef ddlObjectRef(List<Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.isWord("TABLE") || token.isWord("COLUMNFAMILY") || token.isWord("KEYSPACE")
                    || token.isWord("TYPE") || token.isWord("VIEW")) {
                return parseRefSkipping(tokens, i + 1, Set.of("IF", "NOT", "EXISTS"));
            }
        }
        return null;
    }

    /** OR-folds top-level WHERE and collects every FROM/INTO/ON table ref into {@code tables}. */
    private static boolean collectRefsAndWhere(List<Token> tokens, Set<String> tables) {
        boolean hasWhere = false;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.kind() != Kind.WORD) {
                continue;
            }
            if (token.isWord("WHERE")) {
                hasWhere |= token.depth() == 0;
            } else if (REF_INTRODUCERS.contains(token.value())) {
                var parsed = parseRef(tokens, i + 1);
                if (parsed != null) {
                    tables.add(parsed.ref().normalized());
                }
            }
        }
        return hasWhere;
    }

    // ---- table-ref parsing ------------------------------------------------------------------

    private record ParsedRef(CqlTableRef ref, int nextIndex) {
    }

    private static CqlTableRef refAfter(List<Token> tokens, String keyword) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).depth() == 0 && tokens.get(i).isWord(keyword)) {
                var parsed = parseRef(tokens, i + 1);
                return parsed == null ? null : parsed.ref();
            }
        }
        return null;
    }

    private static ParsedRef parseRefSkipping(List<Token> tokens, int i, Set<String> skipWords) {
        while (i < tokens.size() && tokens.get(i).kind() == Kind.WORD
                && skipWords.contains(tokens.get(i).value())) {
            i++;
        }
        return parseRef(tokens, i);
    }

    /** Parse a {@code [keyspace.]table} identifier path starting at {@code i}. */
    private static ParsedRef parseRef(List<Token> tokens, int i) {
        if (i >= tokens.size() || !isIdentifier(tokens.get(i))) {
            return null;
        }
        var first = tokens.get(i).identifier();
        if (i + 2 < tokens.size() && tokens.get(i + 1).isSymbol(".") && isIdentifier(tokens.get(i + 2))) {
            var second = tokens.get(i + 2).identifier();
            return new ParsedRef(new CqlTableRef(first, second), i + 3);
        }
        return new ParsedRef(new CqlTableRef(null, first), i + 1);
    }

    private static boolean isIdentifier(Token token) {
        return token.kind() == Kind.WORD || token.kind() == Kind.QUOTED_IDENT;
    }

    private static boolean hasTopLevelWord(List<Token> tokens, String word) {
        return tokens.stream().anyMatch(t -> t.depth() == 0 && t.isWord(word));
    }

    private static String wordAt(List<Token> tokens, int i) {
        return i < tokens.size() && tokens.get(i).kind() == Kind.WORD ? tokens.get(i).value() : null;
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
