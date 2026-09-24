package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.ColumnReference;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Block;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.RollbackStatement;
import net.sf.jsqlparser.statement.SavepointStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
class SqlParserServiceImpl implements SqlParserService {

    private static final String DDL_PACKAGE_PREFIX = "net.sf.jsqlparser.statement.";

    private static final List<String> DDL_SUBPACKAGES =
            List.of("create", "alter", "drop", "truncate");

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public SqlParseResult parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new InvalidSqlException(msg("error.sql_empty"));
        }
        var boundary = TransactionMarkerScanner.scan(sql);
        return switch (boundary.kind()) {
            case NONE -> parseSingle(sql);
            case UNMATCHED_BEGIN -> throw new InvalidSqlException(msg("error.transaction_unmatched_begin"));
            case UNMATCHED_COMMIT -> throw new InvalidSqlException(msg("error.transaction_unmatched_commit"));
            case BOTH -> parseTransaction(sql, boundary);
        };
    }

    private SqlParseResult parseSingle(String sql) {
        var statements = parseStatementsOrThrow(sql);
        if (statements.isEmpty()) {
            throw new InvalidSqlException(msg("error.sql_no_statement"));
        }
        if (statements.size() > 1) {
            throw new InvalidSqlException(msg("error.sql_multiple_statements"));
        }
        var statement = statements.get(0);
        var type = classify(statement);
        var analysis = analyze(statement, type);
        return new SqlParseResult(type, false, List.of(sql), analysis.tables(),
                hasWhere(statement), hasLimit(statement), analysis.columns(), isDataQuery(type));
    }

    private SqlParseResult parseTransaction(String sql, TransactionMarkerScanner.Boundary boundary) {
        var innerSql = sql.substring(boundary.bodyStart(), boundary.bodyEnd());
        if (innerSql.isBlank()) {
            throw new InvalidSqlException(msg("error.transaction_empty_body"));
        }
        var statements = parseStatementsOrThrow(innerSql);
        if (statements.isEmpty()) {
            throw new InvalidSqlException(msg("error.transaction_empty_body"));
        }
        // Defensive: JSqlParser parses transaction-control statements as Commit / RollbackStatement
        // / SavepointStatement / Block — they must never appear inside an already-unwrapped body.
        boolean hasSelect = false;
        boolean hasDml = false;
        for (Statement statement : statements) {
            if (statement instanceof Commit || statement instanceof Block) {
                throw new InvalidSqlException(msg("error.transaction_nested_not_allowed"));
            }
            if (statement instanceof RollbackStatement) {
                throw new InvalidSqlException(msg("error.transaction_rollback_not_allowed"));
            }
            if (statement instanceof SavepointStatement) {
                throw new InvalidSqlException(msg("error.transaction_savepoint_not_allowed"));
            }
            QueryType type = classify(statement);
            switch (type) {
                case SELECT -> hasSelect = true;
                case INSERT, UPDATE, DELETE -> hasDml = true;
                case DDL -> throw new InvalidSqlException(msg("error.transaction_ddl_not_allowed"));
                case OTHER -> throw new InvalidSqlException(msg("error.transaction_other_not_allowed"));
            }
        }
        if (hasSelect && hasDml) {
            throw new InvalidSqlException(msg("error.transaction_mixed_select_dml"));
        }
        if (hasSelect) {
            throw new InvalidSqlException(msg("error.transaction_select_only"));
        }
        var representativeType = classify(statements.get(0));
        var statementSlices = sliceStatements(statements);
        var referencedTables = new HashSet<String>();
        var referencedColumns = new HashSet<ColumnReference>();
        boolean anyWhere = false;
        boolean anyLimit = false;
        for (Statement statement : statements) {
            var analysis = analyze(statement, classify(statement));
            referencedTables.addAll(analysis.tables());
            referencedColumns.addAll(analysis.columns());
            anyWhere = anyWhere || hasWhere(statement);
            anyLimit = anyLimit || hasLimit(statement);
        }
        // Every inner statement is INSERT / UPDATE / DELETE here, so each was fully analyzed.
        return new SqlParseResult(representativeType, true, statementSlices, referencedTables,
                anyWhere, anyLimit, referencedColumns, true);
    }

    private List<Statement> parseStatementsOrThrow(String sql) {
        Statements parsed;
        try {
            parsed = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException ex) {
            throw new InvalidSqlException(msg("error.sql_parse_failed"), ex);
        }
        if (parsed == null) {
            return List.of();
        }
        // Statements extends ArrayList<Statement> — usable directly as the result list.
        return parsed;
    }

    private static List<String> sliceStatements(List<Statement> statements) {
        var out = new ArrayList<String>(statements.size());
        for (Statement statement : statements) {
            // JSqlParser deparses each statement to a canonical form without a trailing semicolon.
            // Re-appending ';' lets the executor issue them through PreparedStatement individually
            // without the inner stream being one giant batch.
            out.add(statement.toString());
        }
        return out;
    }

    /**
     * Collects the referenced tables and refuses a statement that writes data from inside a query
     * shape (a data-modifying {@code WITH} item, {@code SELECT … INTO}). Such a statement would
     * otherwise be classified by its outer shape — a {@code SELECT} needing only {@code can_read} —
     * so it is rejected outright rather than reclassified. For {@code SELECT} / {@code INSERT} /
     * {@code UPDATE} / {@code DELETE} a traversal failure is fatal too: an empty or partial table set
     * would let the allow-list check pass without having seen every table — or every column (#935).
     */
    private Analysis analyze(Statement statement, QueryType type) {
        SqlStatementInspector.Inspection inspection;
        try {
            inspection = SqlStatementInspector.inspect(statement);
        } catch (RuntimeException ex) {
            if (type == QueryType.DDL || type == QueryType.OTHER) {
                // JSqlParser raises UnsupportedOperationException on a handful of non-DML shapes.
                // DDL is gated by canDdl and OTHER needs write access, neither via the allow-list.
                return new Analysis(Set.of(), Set.of());
            }
            throw new InvalidSqlException(msg("error.sql_analysis_failed"), ex);
        }
        if (inspection.writesData()) {
            throw new InvalidSqlException(msg("error.sql_embedded_write_not_allowed"));
        }
        var out = new HashSet<String>(inspection.tables().size());
        for (String name : inspection.tables()) {
            out.add(normalizeIdentifier(name));
        }
        return new Analysis(out, inspection.columns());
    }

    private record Analysis(Set<String> tables, Set<ColumnReference> columns) {
    }

    private static boolean isDataQuery(QueryType type) {
        return type == QueryType.SELECT || type == QueryType.INSERT
                || type == QueryType.UPDATE || type == QueryType.DELETE;
    }

    /**
     * Collapse a {@code schema.table} (or bare {@code table}) reference into a comparable form:
     * quotes stripped, ASCII-lowercased. PostgreSQL folds unquoted identifiers to lower-case at
     * resolution time; quoted identifiers preserve case. AccessFlow's v1.0 allow-list match is
     * case-insensitive across the board so admin-typed entries match user SQL regardless of
     * quoting style.
     */
    static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return "";
        }
        var stripped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '`' || c == '[' || c == ']') {
                continue;
            }
            stripped.append(c);
        }
        return stripped.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean hasWhere(Statement statement) {
        return switch (statement) {
            case Select select -> {
                var plain = select.getPlainSelect();
                yield plain != null && plain.getWhere() != null;
            }
            case Update update -> update.getWhere() != null;
            case Delete delete -> delete.getWhere() != null;
            default -> false;
        };
    }

    private static boolean hasLimit(Statement statement) {
        // LIMIT is only meaningful for SELECT.
        return statement instanceof Select select && select.getLimit() != null;
    }

    private static QueryType classify(Statement statement) {
        return switch (statement) {
            case Select ignored -> QueryType.SELECT;
            case Insert ignored -> QueryType.INSERT;
            case Update ignored -> QueryType.UPDATE;
            case Delete ignored -> QueryType.DELETE;
            default -> isDdl(statement) ? QueryType.DDL : QueryType.OTHER;
        };
    }

    private static boolean isDdl(Statement statement) {
        String packageName = statement.getClass().getPackageName();
        if (!packageName.startsWith(DDL_PACKAGE_PREFIX)) {
            return false;
        }
        String tail = packageName.substring(DDL_PACKAGE_PREFIX.length());
        for (String ddlSubpackage : DDL_SUBPACKAGES) {
            if (tail.equals(ddlSubpackage) || tail.startsWith(ddlSubpackage + ".")) {
                return true;
            }
        }
        return false;
    }
}
