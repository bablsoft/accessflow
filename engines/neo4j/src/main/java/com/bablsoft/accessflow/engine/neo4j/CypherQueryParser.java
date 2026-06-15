package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.engine.neo4j.CypherNodePattern.ClauseKind;
import com.bablsoft.accessflow.engine.neo4j.CypherTokenizer.Kind;
import com.bablsoft.accessflow.engine.neo4j.CypherTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and classifies a submitted Cypher statement without a full AST: a clause classifier over
 * the {@link CypherTokenizer} token stream, mirroring the JSqlParser path's guarantees. Exactly one
 * statement is allowed (a trailing {@code ;} is tolerated). Cypher is clause-based, so the query
 * type is the strongest write clause present: {@code DELETE}/{@code DETACH DELETE}/{@code REMOVE} →
 * DELETE; {@code CREATE}/{@code MERGE} → INSERT; {@code SET} → UPDATE; a pure read
 * ({@code MATCH … RETURN}, {@code SHOW …}) → SELECT. Schema commands ({@code CREATE}/{@code DROP}/
 * {@code ALTER} of an INDEX / CONSTRAINT / DATABASE / ALIAS / USER / ROLE) → DDL. Everything that
 * could run server-side code or exfiltrate — {@code LOAD CSV} and {@code CALL} of a procedure
 * outside a small read-only allow-list (the Cypher counterpart of the MongoDB engine's
 * {@code $where} ban) — and multi-statement input fail closed with {@link InvalidSqlException}
 * (HTTP 422). {@code referencedTables} carries every node label and relationship type the statement
 * touches (lowercased) for the host's allow-list check.
 */
class CypherQueryParser {

    /** {@code CALL <name>(…)} procedures permitted (read-only schema introspection helpers). */
    private static final Set<String> ALLOWED_PROCEDURES = Set.of(
            "db.labels", "db.relationshiptypes", "db.propertykeys", "db.schema.visualization",
            "db.schema.nodetypeproperties", "db.schema.reltypeproperties");

    /** DDL object keywords following CREATE/DROP/ALTER that make the statement schema/admin DDL. */
    private static final Set<String> DDL_OBJECTS = Set.of(
            "INDEX", "CONSTRAINT", "DATABASE", "ALIAS", "USER", "ROLE", "SERVER", "PRIVILEGE",
            "COMPOSITE", "HOME");
    /** Index-type adjectives that may precede INDEX (so {@code CREATE TEXT INDEX …} is still DDL). */
    private static final Set<String> INDEX_ADJECTIVES = Set.of(
            "FULLTEXT", "TEXT", "POINT", "RANGE", "LOOKUP", "VECTOR", "BTREE");
    /** Depth-0 keywords that begin / end a clause (the pattern-region and WHERE-scope boundaries). */
    private static final Set<String> CLAUSE_BOUNDARIES = Set.of(
            "MATCH", "OPTIONAL", "WHERE", "RETURN", "WITH", "CREATE", "MERGE", "SET", "DELETE",
            "DETACH", "REMOVE", "UNWIND", "CALL", "FOREACH", "ORDER", "SKIP", "LIMIT", "UNION",
            "USE", "ON", "YIELD");

    private final EngineMessages messages;

    CypherQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result for the workflow layer (query type, references, routing hints). */
    SqlParseResult parse(String query) {
        var statement = parseStatement(query);
        boolean hasLimit = hasTopLevelWord(statement.tokens(), "LIMIT");
        boolean hasWhere = hasTopLevelWord(statement.tokens(), "WHERE");
        return new SqlParseResult(statement.kind().queryType(), false, List.of(query),
                statement.references(), hasWhere, hasLimit);
    }

    /** Full parse to the executable {@link CypherStatement}; reused by the executor. */
    CypherStatement parseStatement(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.neo4j.blank");
        }
        try {
            var tokens = singleStatement(CypherTokenizer.tokenize(query));
            rejectForbidden(tokens);
            var kind = classify(tokens);
            var references = collectReferences(tokens);
            var patterns = kind == CypherStatementKind.DDL
                    ? List.<CypherNodePattern>of()
                    : extractNodePatterns(tokens);
            return new CypherStatement(query, kind, references, patterns, tokens);
        } catch (CypherParseException ex) {
            throw invalid(ex.messageKey(), ex.args());
        }
    }

    // ---- statement splitting ------------------------------------------------------------------

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
            throw new CypherParseException("error.neo4j.blank");
        }
        if (statements.size() > 1) {
            throw new CypherParseException("error.neo4j.multiple_statements");
        }
        return statements.get(0);
    }

    // ---- forbidden constructs -----------------------------------------------------------------

    private static void rejectForbidden(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.isWord("LOAD") && "CSV".equals(wordAt(tokens, i + 1))) {
                throw new CypherParseException("error.neo4j.load_csv_forbidden");
            }
            if (token.isWord("CALL")) {
                rejectDisallowedProcedure(tokens, i);
            }
        }
    }

    /** A top-level {@code CALL name(…)} must name an allow-listed procedure; {@code CALL &#123;…&#125;} is a subquery. */
    private static void rejectDisallowedProcedure(List<Token> tokens, int callIndex) {
        int i = callIndex + 1;
        if (i >= tokens.size() || tokens.get(i).isSymbol("{")) {
            return; // CALL { … } subquery — not a procedure call.
        }
        var name = new StringBuilder();
        while (i < tokens.size()) {
            var token = tokens.get(i);
            if (token.isIdentifier()) {
                name.append(token.identifier());
            } else if (token.isSymbol(".")) {
                name.append('.');
            } else {
                break;
            }
            i++;
        }
        if (name.isEmpty()) {
            return;
        }
        if (!ALLOWED_PROCEDURES.contains(name.toString().toLowerCase(Locale.ROOT))) {
            throw new CypherParseException("error.neo4j.procedure_forbidden", name.toString());
        }
    }

    // ---- classification -----------------------------------------------------------------------

    private static CypherStatementKind classify(List<Token> tokens) {
        int leadIndex = firstClauseKeyword(tokens);
        if (leadIndex < 0) {
            throw new CypherParseException("error.neo4j.unsupported_statement",
                    tokens.isEmpty() ? "" : tokens.get(0).text());
        }
        var lead = tokens.get(leadIndex).value();
        if (lead.equals("SHOW")) {
            return CypherStatementKind.SELECT;
        }
        if (lead.equals("DROP") || lead.equals("ALTER")) {
            return CypherStatementKind.DDL;
        }
        if (lead.equals("CREATE") && isSchemaCreate(tokens, leadIndex)) {
            return CypherStatementKind.DDL;
        }
        return classifyDml(tokens);
    }

    /** {@code CREATE} is DDL when the next word (past OR REPLACE) is a schema object / index adjective. */
    private static boolean isSchemaCreate(List<Token> tokens, int createIndex) {
        int i = createIndex + 1;
        while ("OR".equals(wordAt(tokens, i)) || "REPLACE".equals(wordAt(tokens, i))) {
            i++;
        }
        var next = wordAt(tokens, i);
        return next != null && (DDL_OBJECTS.contains(next) || INDEX_ADJECTIVES.contains(next));
    }

    private static CypherStatementKind classifyDml(List<Token> tokens) {
        boolean delete = false;
        boolean write = false;
        boolean set = false;
        for (var token : tokens) {
            if (token.depth() != 0 || token.kind() != Kind.WORD) {
                continue;
            }
            switch (token.value()) {
                case "DELETE", "REMOVE" -> delete = true;
                case "CREATE", "MERGE" -> write = true;
                case "SET" -> set = true;
                default -> { /* read clause */ }
            }
        }
        if (delete) {
            return CypherStatementKind.DELETE;
        }
        if (write) {
            return CypherStatementKind.INSERT;
        }
        return set ? CypherStatementKind.UPDATE : CypherStatementKind.SELECT;
    }

    /** The first recognized leading clause keyword (skipping a {@code USE db} prefix). */
    private static int firstClauseKeyword(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() != 0 || token.kind() != Kind.WORD) {
                continue;
            }
            var value = token.value();
            if (value.equals("USE")) {
                continue;
            }
            if (isLeadingClause(value)) {
                return i;
            }
            return -1;
        }
        return -1;
    }

    private static boolean isLeadingClause(String value) {
        return switch (value) {
            case "MATCH", "OPTIONAL", "CREATE", "MERGE", "WITH", "UNWIND", "CALL", "RETURN",
                 "FOREACH", "SHOW", "DROP", "ALTER", "SET", "DELETE", "DETACH", "REMOVE", "LOAD" ->
                    true;
            default -> false;
        };
    }

    // ---- references (labels + relationship types) ---------------------------------------------

    private static Set<String> collectReferences(List<Token> tokens) {
        var references = new LinkedHashSet<String>();
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            // A label/type colon lives in a node ( ) / relationship [ ] pattern or a bare predicate,
            // never in a map literal { … } whose colons are key separators.
            if (!token.isSymbol(":") || token.enclosing() == '{') {
                continue;
            }
            i = collectLabelChain(tokens, i, references);
        }
        return references;
    }

    /** From a leading {@code :}, collect the {@code :A:B}, {@code :A|B}, {@code :A&B} label chain. */
    private static int collectLabelChain(List<Token> tokens, int colonIndex, Set<String> out) {
        int i = colonIndex;
        while (i < tokens.size() && tokens.get(i).kind() == Kind.SYMBOL
                && (tokens.get(i).text().equals(":") || tokens.get(i).text().equals("|")
                || tokens.get(i).text().equals("&"))) {
            if (i + 1 < tokens.size() && tokens.get(i + 1).isIdentifier()) {
                out.add(tokens.get(i + 1).identifier().toLowerCase(Locale.ROOT));
                i += 2;
            } else {
                break;
            }
        }
        return i - 1;
    }

    // ---- clause-level node patterns (for row security) ----------------------------------------

    private static List<CypherNodePattern> extractNodePatterns(List<Token> tokens) {
        var patterns = new ArrayList<CypherNodePattern>();
        ClauseKind region = null;
        int anchor = -1;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.kind() == Kind.WORD
                    && CLAUSE_BOUNDARIES.contains(token.value())) {
                region = regionFor(tokens, i);
                anchor = i;
                continue;
            }
            if (region != null && token.depth() == 0 && token.isSymbol("(")) {
                patterns.add(parseNodePattern(tokens, i, region, anchor));
            }
        }
        return patterns;
    }

    /** A clause keyword starts a node-pattern region only for MATCH / OPTIONAL MATCH / CREATE / MERGE. */
    private static ClauseKind regionFor(List<Token> tokens, int index) {
        var value = tokens.get(index).value();
        if (value.equals("MATCH")) {
            return ClauseKind.MATCH;
        }
        if (value.equals("OPTIONAL") && "MATCH".equals(wordAt(tokens, index + 1))) {
            return ClauseKind.MATCH;
        }
        if (value.equals("CREATE") || value.equals("MERGE")) {
            return ClauseKind.WRITE;
        }
        return null;
    }

    /** Parse {@code (variable :Label …)} head; props/relationships beyond the labels are ignored. */
    private static CypherNodePattern parseNodePattern(List<Token> tokens, int openParen,
                                                      ClauseKind region, int anchor) {
        int i = openParen + 1;
        String variable = null;
        if (i < tokens.size() && tokens.get(i).isIdentifier()) {
            variable = tokens.get(i).identifier();
            i++;
        }
        var labels = new ArrayList<String>();
        while (i < tokens.size() && tokens.get(i).kind() == Kind.SYMBOL
                && (tokens.get(i).text().equals(":") || tokens.get(i).text().equals("|")
                || tokens.get(i).text().equals("&"))) {
            if (i + 1 < tokens.size() && tokens.get(i + 1).isIdentifier()) {
                labels.add(tokens.get(i + 1).identifier());
                i += 2;
            } else {
                break;
            }
        }
        return new CypherNodePattern(variable, List.copyOf(labels), region, anchor);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static boolean hasTopLevelWord(List<Token> tokens, String word) {
        return tokens.stream().anyMatch(t -> t.depth() == 0 && t.isWord(word));
    }

    private static String wordAt(List<Token> tokens, int i) {
        return i >= 0 && i < tokens.size() && tokens.get(i).kind() == Kind.WORD
                ? tokens.get(i).value() : null;
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
