package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.proxy.api.InvalidSqlException;
import com.partqam.accessflow.proxy.api.SqlParseResult;
import com.partqam.accessflow.proxy.api.SqlParserService;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

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
        Statements parsed;
        try {
            parsed = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException ex) {
            throw new InvalidSqlException(msg("error.sql_parse_failed"), ex);
        }
        List<Statement> statements = parsed.getStatements();
        if (statements == null || statements.isEmpty()) {
            throw new InvalidSqlException(msg("error.sql_no_statement"));
        }
        if (statements.size() > 1) {
            throw new InvalidSqlException(msg("error.sql_multiple_statements"));
        }
        Statement statement = statements.get(0);
        return new SqlParseResult(classify(statement), statement);
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
