package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.engine.dynamodb.PartiQlTokenizer.Kind;
import com.bablsoft.accessflow.engine.dynamodb.PartiQlTokenizer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses and classifies a submitted DynamoDB query without a full AST: a keyword classifier over the
 * {@link PartiQlTokenizer} token stream, mirroring the JSqlParser path's guarantees. A submission is
 * either a single PartiQL statement or a JSON table-management command (it begins with {@code &#123;}) —
 * {@link #isJsonCommand(String)} dispatches between the two. PartiQL classification: {@code SELECT} →
 * SELECT; {@code INSERT} → INSERT; {@code UPDATE} → UPDATE; {@code DELETE} → DELETE. The JSON form
 * ({@link DynamoDbDdlCommand}) wraps a single {@code CreateTable} / {@code DeleteTable} /
 * {@code UpdateTable} → DDL. Everything else fails closed with {@link InvalidSqlException} (HTTP 422)
 * — including multiple statements and transaction/batch verbs ({@code EXECUTE TRANSACTION},
 * {@code BEGIN}), the DynamoDB counterpart of the SQL engine's batch ban. {@code referencedTables}
 * carries the (case-preserved) table name for the host's allow-list check; an index access resolves
 * to its base table.
 */
class PartiQlQueryParser {

    private static final Set<String> DML_VERBS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");
    private static final Set<String> TRANSACTION_VERBS = Set.of(
            "EXECUTE", "BEGIN", "START", "COMMIT", "ROLLBACK");

    private final EngineMessages messages;

    PartiQlQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Whether the submission is a JSON table-management command rather than a PartiQL statement. */
    static boolean isJsonCommand(String query) {
        return query != null && query.strip().startsWith("{");
    }

    /** Engine-neutral parse result for the workflow layer (query type, tables, routing hints). */
    SqlParseResult parse(String query) {
        if (isJsonCommand(query)) {
            var command = DynamoDbDdlCommand.parse(query, messages);
            return new SqlParseResult(QueryType.DDL, false, List.of(query),
                    Set.of(command.tableName()), false, false);
        }
        var statement = parseStatement(query);
        return new SqlParseResult(statement.kind().queryType(), false, List.of(query),
                statement.tables(), statement.hasWhere(), false);
    }

    /** Full parse to the executable {@link PartiQlStatement}; reused by the executor. */
    PartiQlStatement parseStatement(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.dynamodb.blank");
        }
        try {
            var allTokens = PartiQlTokenizer.tokenize(query);
            var tokens = singleStatement(allTokens);
            return classify(query, tokens);
        } catch (PartiQlParseException ex) {
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
            throw new PartiQlParseException("error.dynamodb.blank");
        }
        if (statements.size() > 1) {
            throw new PartiQlParseException("error.dynamodb.multiple_statements");
        }
        return statements.get(0);
    }

    // ---- classification ---------------------------------------------------------------------

    private PartiQlStatement classify(String sql, List<Token> tokens) {
        var first = tokens.get(0);
        if (first.kind() != Kind.WORD) {
            throw new PartiQlParseException("error.dynamodb.unsupported_statement", first.text());
        }
        if (TRANSACTION_VERBS.contains(first.value())) {
            throw new PartiQlParseException("error.dynamodb.transaction_forbidden");
        }
        if (!DML_VERBS.contains(first.value())) {
            throw new PartiQlParseException("error.dynamodb.unsupported_statement", first.text());
        }
        var kind = kindOf(first.value());
        PartiQlTableRef target = switch (kind) {
            case SELECT, DELETE -> refAfter(tokens, "FROM");
            case INSERT -> refAfter(tokens, "INTO");
            case UPDATE -> parseRef(tokens, 1);
            default -> null;
        };
        if (target == null) {
            throw new PartiQlParseException("error.dynamodb.table_required");
        }
        boolean hasWhere = hasTopLevelWord(tokens, "WHERE");
        return new PartiQlStatement(sql, kind, target, Set.of(target.normalized()), hasWhere,
                List.copyOf(tokens));
    }

    private static PartiQlStatementKind kindOf(String verb) {
        return switch (verb) {
            case "SELECT" -> PartiQlStatementKind.SELECT;
            case "INSERT" -> PartiQlStatementKind.INSERT;
            case "UPDATE" -> PartiQlStatementKind.UPDATE;
            case "DELETE" -> PartiQlStatementKind.DELETE;
            default -> throw new PartiQlParseException("error.dynamodb.unsupported_statement", verb);
        };
    }

    // ---- table-ref parsing ------------------------------------------------------------------

    private static PartiQlTableRef refAfter(List<Token> tokens, String keyword) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).depth() == 0 && tokens.get(i).isWord(keyword)) {
                return parseRef(tokens, i + 1);
            }
        }
        return null;
    }

    /** Parse a {@code "table"[."index"]} reference starting at {@code i}; an index resolves to the base table. */
    private static PartiQlTableRef parseRef(List<Token> tokens, int i) {
        if (i >= tokens.size() || !isIdentifier(tokens.get(i))) {
            return null;
        }
        return new PartiQlTableRef(tokens.get(i).identifier());
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
