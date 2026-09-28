package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.create.index.CreateIndex;

import java.util.List;
import java.util.Map;

/**
 * A PostgreSQL {@code CREATE [UNIQUE] INDEX} without {@code CONCURRENTLY} (#1079): the build holds a
 * lock that blocks writes to the table until it finishes. PostgreSQL only — other engines build
 * indexes online by default or have no {@code CONCURRENTLY} form.
 */
public final class CreateIndexWithoutConcurrentlyRule implements SqlRule {

    public static final String ID = "create_index_without_concurrently";

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
    public boolean appliesTo(DbType dbType) {
        return dbType == DbType.POSTGRESQL;
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("table");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        if (context.statement() instanceof CreateIndex create && !create.isConcurrently()) {
            return List.of(context.finding(this, create.getTable(),
                    Map.of("table", TableNames.normalize(create.getTable()))));
        }
        return List.of();
    }
}
