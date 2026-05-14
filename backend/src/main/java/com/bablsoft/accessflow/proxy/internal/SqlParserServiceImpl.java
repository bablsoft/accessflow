package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.proxy.api.InvalidSqlException;
import com.bablsoft.accessflow.proxy.api.SqlParseResult;
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
import java.util.List;

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
        return new SqlParseResult(classify(statements.get(0)), sql);
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
        return new SqlParseResult(representativeType, true, statementSlices);
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
        var statements = parsed.getStatements();
        return statements == null ? List.of() : statements;
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
