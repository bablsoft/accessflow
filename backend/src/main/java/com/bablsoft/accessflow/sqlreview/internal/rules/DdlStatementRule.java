package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.truncate.Truncate;

import java.util.List;
import java.util.Map;

/**
 * Any DDL — CREATE / ALTER / DROP / TRUNCATE. Broader than {@code drop_statement} and
 * {@code truncate_statement}; an admin who wants one signal per DDL keeps this and turns those off.
 */
public final class DdlStatementRule implements SqlRule {

    public static final String ID = "ddl_statement";

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.SCHEMA_CHANGE;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.WARN;
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("statement_type");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var statement = context.statement();
        if (!StatementKinds.isDdl(statement)) {
            return List.of();
        }
        ASTNodeAccess anchor = switch (statement) {
            case Drop drop -> drop.getName();
            case Truncate truncate -> truncate.getTable();
            case Alter alter -> alter.getTable();
            case CreateTable create -> create.getTable();
            default -> null;
        };
        return List.of(context.finding(this, anchor, Map.of("statement_type", StatementKinds.typeName(statement))));
    }
}
