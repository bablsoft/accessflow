package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Map;

/**
 * {@code INSERT} / {@code UPDATE} / {@code DELETE} submitted outside a {@code BEGIN … COMMIT}
 * envelope — no rollback boundary if it goes wrong. Uses the parser's {@code transactional} flag.
 */
public final class DmlWithoutTransactionRule implements SqlRule {

    public static final String ID = "dml_without_transaction";

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.STATEMENT_SAFETY;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.WARN;
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        if (context.transactional() || !StatementKinds.isDml(context.statement())) {
            return List.of();
        }
        ASTNodeAccess anchor = switch (context.statement()) {
            case Insert insert -> insert.getTable();
            case Update update -> update.getTable();
            case Delete delete -> delete.getTable();
            default -> null;
        };
        return List.of(context.finding(this, anchor, Map.of()));
    }
}
