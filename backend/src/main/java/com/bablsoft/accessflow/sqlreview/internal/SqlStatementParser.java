package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleContext;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the proxy parser's string-only {@link SqlParseResult} back into JSqlParser ASTs (#862).
 * The proxy module cannot expose JSqlParser types across the module boundary, so the rules
 * re-parse each statement slice. A single statement is the verbatim submission, so its AST line
 * numbers are real; a {@code BEGIN…COMMIT} envelope yields deparsed slices, whose lines would all
 * read 1 — those contexts are marked {@code lineNumbersKnown = false}. A slice that fails to
 * re-parse (it parsed once already, so this is defensive) is skipped and logged.
 */
final class SqlStatementParser {

    private static final Logger log = LoggerFactory.getLogger(SqlStatementParser.class);

    private SqlStatementParser() {
    }

    static List<SqlRuleContext> parse(SqlParseResult parsed) {
        var slices = parsed.statements();
        var contexts = new ArrayList<SqlRuleContext>(slices.size());
        for (int index = 0; index < slices.size(); index++) {
            try {
                var statement = CCJSqlParserUtil.parse(slices.get(index));
                contexts.add(new SqlRuleContext(index, statement, parsed.transactional(),
                        !parsed.transactional()));
            } catch (JSQLParserException ex) {
                log.warn("SQL review: statement {} could not be re-parsed and is not evaluated", index);
            }
        }
        return contexts;
    }
}
