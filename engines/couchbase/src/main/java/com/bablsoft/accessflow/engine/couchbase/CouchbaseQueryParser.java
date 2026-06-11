package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.engine.couchbase.SqlPlusPlusTokenizer.Kind;
import com.bablsoft.accessflow.engine.couchbase.SqlPlusPlusTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and classifies a submitted SQL++ (N1QL) statement without a full AST: a keyword
 * classifier over the {@link SqlPlusPlusTokenizer} token stream, mirroring the JSqlParser path's
 * guarantees. Exactly one statement is allowed (a trailing {@code ;} is tolerated). Classification:
 * {@code SELECT} → SELECT; {@code INSERT}/{@code UPSERT} → INSERT; {@code UPDATE}/{@code MERGE} →
 * UPDATE; {@code DELETE} → DELETE; {@code CREATE}/{@code DROP} of {@code [PRIMARY] INDEX} /
 * {@code SCOPE} / {@code COLLECTION} → DDL. Everything else fails closed with
 * {@link InvalidSqlException} (HTTP 422) — including the {@code CURL()} function (server-side
 * exfiltration), JavaScript UDF statements ({@code CREATE}/{@code EXECUTE}/{@code DROP FUNCTION}),
 * and {@code system:*} keyspaces, the SQL++ counterparts of the MongoDB engine's {@code $where}
 * ban. {@code referencedTables} carries every referenced keyspace path (lowercased,
 * {@code collection} or {@code bucket.scope.collection}) for the host's allow-list check.
 */
class CouchbaseQueryParser {

    private static final Set<String> MAIN_VERBS =
            Set.of("SELECT", "INSERT", "UPSERT", "UPDATE", "DELETE", "MERGE");
    private static final Set<String> DDL_OBJECTS = Set.of("PRIMARY", "INDEX", "SCOPE", "COLLECTION");
    /** Words that may directly follow a keyspace ref and therefore are never its alias. */
    private static final Set<String> NON_ALIAS_WORDS = Set.of(
            "AS", "ON", "USE", "USING", "SET", "UNSET", "WHERE", "LET", "LETTING", "GROUP",
            "HAVING", "WINDOW", "ORDER", "LIMIT", "OFFSET", "RETURNING", "JOIN", "NEST", "UNNEST",
            "INNER", "LEFT", "RIGHT", "OUTER", "WHEN", "UNION", "INTERSECT", "EXCEPT", "VALUES",
            "WITH", "IF", "NOT", "EXISTS");

    private final EngineMessages messages;

    CouchbaseQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result for the workflow layer (query type, keyspaces, routing hints). */
    SqlParseResult parse(String query) {
        var statement = parseStatement(query);
        return new SqlParseResult(
                statement.kind().queryType(),
                false,
                List.of(query),
                statement.keyspaces(),
                statement.hasWhere(),
                statement.hasLimit() && statement.kind().isRead());
    }

    /** Full parse to the executable {@link CouchbaseStatement}; reused by the executor. */
    CouchbaseStatement parseStatement(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.couchbase.blank");
        }
        try {
            var tokens = singleStatement(SqlPlusPlusTokenizer.tokenize(query));
            rejectForbidden(tokens);
            return classify(query, tokens);
        } catch (CouchbaseParseException ex) {
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
            throw new CouchbaseParseException("error.couchbase.blank");
        }
        if (statements.size() > 1) {
            throw new CouchbaseParseException("error.couchbase.multiple_statements");
        }
        return statements.get(0);
    }

    // ---- forbidden constructs ---------------------------------------------------------------

    private static void rejectForbidden(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            var next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            if (token.isWord("CURL") && next != null && next.isSymbol("(")) {
                throw new CouchbaseParseException("error.couchbase.curl_forbidden");
            }
            if (token.isWord("SYSTEM") && next != null && next.isSymbol(":")) {
                throw new CouchbaseParseException("error.couchbase.system_keyspace_forbidden");
            }
            if (token.kind() == Kind.QUOTED_IDENT
                    && token.value().toLowerCase(Locale.ROOT).startsWith("system:")) {
                throw new CouchbaseParseException("error.couchbase.system_keyspace_forbidden");
            }
            if (token.kind() == Kind.WORD && isUdfStatementHead(tokens, i, token)) {
                throw new CouchbaseParseException("error.couchbase.udf_forbidden");
            }
        }
    }

    /** {@code CREATE [OR REPLACE] FUNCTION}, {@code EXECUTE FUNCTION}, {@code DROP FUNCTION}. */
    private static boolean isUdfStatementHead(List<Token> tokens, int i, Token token) {
        if (!token.value().equals("CREATE") && !token.value().equals("EXECUTE")
                && !token.value().equals("DROP")) {
            return false;
        }
        var next = wordAt(tokens, i + 1);
        if (next == null) {
            return false;
        }
        if (next.equals("FUNCTION")) {
            return true;
        }
        return token.value().equals("CREATE") && next.equals("OR")
                && "REPLACE".equals(wordAt(tokens, i + 2))
                && "FUNCTION".equals(wordAt(tokens, i + 3));
    }

    private static String wordAt(List<Token> tokens, int i) {
        return i < tokens.size() && tokens.get(i).kind() == Kind.WORD ? tokens.get(i).value() : null;
    }

    // ---- classification ---------------------------------------------------------------------

    private CouchbaseStatement classify(String sql, List<Token> tokens) {
        var first = tokens.get(0);
        if (first.kind() != Kind.WORD) {
            throw new CouchbaseParseException("error.couchbase.unsupported_statement", first.text());
        }
        boolean hasCte = first.value().equals("WITH");
        CouchbaseStatementKind kind;
        int verbIndex;
        if (hasCte) {
            verbIndex = mainVerbIndex(tokens);
            kind = kindOf(tokens.get(verbIndex).value());
        } else if (MAIN_VERBS.contains(first.value())) {
            verbIndex = 0;
            kind = kindOf(first.value());
        } else if (first.value().equals("CREATE") || first.value().equals("DROP")) {
            var object = wordAt(tokens, 1);
            if (object == null || !DDL_OBJECTS.contains(object)) {
                throw new CouchbaseParseException("error.couchbase.unsupported_statement",
                        first.text());
            }
            verbIndex = 0;
            kind = CouchbaseStatementKind.DDL;
        } else {
            throw new CouchbaseParseException("error.couchbase.unsupported_statement", first.text());
        }
        return analyse(sql, tokens, kind, verbIndex, hasCte);
    }

    private static int mainVerbIndex(List<Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.kind() == Kind.WORD
                    && MAIN_VERBS.contains(token.value())) {
                return i;
            }
        }
        throw new CouchbaseParseException("error.couchbase.unsupported_statement", "WITH");
    }

    private static CouchbaseStatementKind kindOf(String verb) {
        return switch (verb) {
            case "SELECT" -> CouchbaseStatementKind.SELECT;
            case "INSERT" -> CouchbaseStatementKind.INSERT;
            case "UPSERT" -> CouchbaseStatementKind.UPSERT;
            case "UPDATE" -> CouchbaseStatementKind.UPDATE;
            case "MERGE" -> CouchbaseStatementKind.MERGE;
            case "DELETE" -> CouchbaseStatementKind.DELETE;
            default -> throw new CouchbaseParseException("error.couchbase.unsupported_statement",
                    verb);
        };
    }

    // ---- structural analysis ----------------------------------------------------------------

    private CouchbaseStatement analyse(String sql, List<Token> tokens, CouchbaseStatementKind kind,
                                       int verbIndex, boolean hasCte) {
        boolean hasWhere = false;
        boolean hasLimit = false;
        boolean hasSubquery = false;
        boolean hasJoinLike = false;
        boolean hasUseKeys = false;
        boolean hasSetOperation = false;
        var cteNames = hasCte ? cteNames(tokens, verbIndex) : Set.<String>of();
        var keyspaces = new LinkedHashSet<String>();
        KeyspaceRef target = null;
        String targetAlias = null;

        if (kind == CouchbaseStatementKind.UPDATE) {
            var parsed = parseRef(tokens, verbIndex + 1);
            if (parsed != null) {
                target = parsed.ref();
                targetAlias = aliasAfter(tokens, parsed.nextIndex());
                keyspaces.add(target.normalized());
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.kind() != Kind.WORD) {
                continue;
            }
            switch (token.value()) {
                case "WHERE" -> hasWhere |= token.depth() == 0;
                case "LIMIT" -> hasLimit |= token.depth() == 0;
                // A nested SELECT, or a depth-0 SELECT that is not the main verb of a SELECT
                // statement (i.e. an INSERT/UPSERT … SELECT source). A second depth-0 SELECT
                // inside a SELECT statement is a set-operation branch — hasSetOperation covers it.
                case "SELECT" -> hasSubquery |= i != verbIndex
                        && (token.depth() > 0 || kind != CouchbaseStatementKind.SELECT);
                case "JOIN", "NEST", "UNNEST" -> hasJoinLike = true;
                case "UNION", "INTERSECT", "EXCEPT" -> hasSetOperation |= token.depth() == 0;
                case "KEYS" -> hasUseKeys |= "USE".equals(wordBefore(tokens, i));
                default -> { /* not structural */ }
            }
            if (isKeyspaceIntroducer(tokens, i, token, kind)) {
                var parsed = parseRef(tokens, i + 1);
                if (parsed != null && !isCteAlias(parsed.ref(), cteNames)) {
                    keyspaces.add(parsed.ref().normalized());
                    boolean isTargetPosition = token.depth() == 0 && target == null
                            && targetIntroducer(kind).equals(token.value());
                    if (isTargetPosition) {
                        target = parsed.ref();
                        targetAlias = aliasAfter(tokens, parsed.nextIndex());
                    }
                }
            }
        }
        if (target == null && requiresTarget(kind)) {
            throw new CouchbaseParseException("error.couchbase.keyspace_required");
        }
        return new CouchbaseStatement(sql, kind, target, targetAlias, Set.copyOf(keyspaces),
                hasWhere, hasLimit, hasCte, hasSubquery, hasJoinLike, hasUseKeys, hasSetOperation,
                List.copyOf(tokens));
    }

    /**
     * CTE binding names ({@code WITH x AS (…), y AS (…) SELECT …}): every depth-0 identifier
     * followed by {@code AS} before the main verb. Excluded from {@code referencedTables}, like
     * the JSqlParser path excludes CTE aliases.
     */
    private static Set<String> cteNames(List<Token> tokens, int verbIndex) {
        var names = new LinkedHashSet<String>();
        for (int i = 0; i < verbIndex; i++) {
            var token = tokens.get(i);
            if (token.depth() == 0
                    && (token.kind() == Kind.WORD || token.kind() == Kind.QUOTED_IDENT)
                    && i + 1 < tokens.size() && tokens.get(i + 1).isWord("AS")) {
                names.add(token.value().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static boolean isCteAlias(KeyspaceRef ref, Set<String> cteNames) {
        return ref.parts().size() == 1 && cteNames.contains(ref.lastSegment());
    }

    private static boolean isKeyspaceIntroducer(List<Token> tokens, int i, Token token,
                                                CouchbaseStatementKind kind) {
        return switch (token.value()) {
            case "FROM", "INTO", "JOIN", "NEST" -> true;
            // MERGE source; only a keyspace when not a subquery (parseRef skips "(")
            case "USING" -> kind == CouchbaseStatementKind.MERGE;
            // DDL object positions: CREATE [PRIMARY] INDEX … ON ks, CREATE/DROP SCOPE|COLLECTION ks
            case "ON", "SCOPE", "COLLECTION" -> kind == CouchbaseStatementKind.DDL;
            default -> false;
        };
    }

    private static String targetIntroducer(CouchbaseStatementKind kind) {
        return switch (kind) {
            case SELECT, DELETE -> "FROM";
            case INSERT, UPSERT, MERGE -> "INTO";
            case UPDATE, DDL -> ""; // UPDATE handled separately; DDL has no DML target
        };
    }

    private static boolean requiresTarget(CouchbaseStatementKind kind) {
        return switch (kind) {
            case INSERT, UPSERT, UPDATE, DELETE, MERGE -> true;
            case SELECT, DDL -> false;
        };
    }

    /** Parsed ref + the index of the first token after it. */
    private record ParsedRef(KeyspaceRef ref, int nextIndex) {
    }

    /**
     * Parse a (possibly {@code default:}-namespaced, possibly backticked) dotted keyspace path
     * starting at {@code i}; returns {@code null} when the position does not hold a keyspace (e.g.
     * a {@code (SELECT …)} subquery or an expression).
     */
    private static ParsedRef parseRef(List<Token> tokens, int i) {
        if (i >= tokens.size()) {
            return null;
        }
        // Optional namespace prefix: default:<path> (system: is rejected earlier).
        if (tokens.get(i).isWord("DEFAULT") && i + 1 < tokens.size()
                && tokens.get(i + 1).isSymbol(":")) {
            i += 2;
        }
        var parts = new ArrayList<String>();
        while (i < tokens.size()) {
            var token = tokens.get(i);
            if (token.kind() != Kind.WORD && token.kind() != Kind.QUOTED_IDENT) {
                break;
            }
            parts.add(token.value());
            if (i + 1 < tokens.size() && tokens.get(i + 1).isSymbol(".")) {
                i += 2;
                continue;
            }
            i++;
            break;
        }
        if (parts.isEmpty()) {
            return null;
        }
        return new ParsedRef(new KeyspaceRef(parts), i);
    }

    private static String aliasAfter(List<Token> tokens, int i) {
        if (i < tokens.size() && tokens.get(i).isWord("AS")) {
            i++;
        }
        if (i < tokens.size()) {
            var token = tokens.get(i);
            if (token.kind() == Kind.QUOTED_IDENT) {
                return token.value();
            }
            if (token.kind() == Kind.WORD && !NON_ALIAS_WORDS.contains(token.value())) {
                return token.text();
            }
        }
        return null;
    }

    private static String wordBefore(List<Token> tokens, int i) {
        return i > 0 && tokens.get(i - 1).kind() == Kind.WORD ? tokens.get(i - 1).value() : null;
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
