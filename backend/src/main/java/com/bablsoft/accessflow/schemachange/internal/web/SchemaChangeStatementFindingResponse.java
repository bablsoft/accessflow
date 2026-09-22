package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;

import java.util.UUID;

/**
 * One deterministic-SQL-review finding on the wire — the {@code POST /sql-review/evaluate} shape
 * plus the change set's {@code statementIndex} and the datasource whose ruleset produced it;
 * {@code lineNumber} is omitted when unknown, {@code message} is localized.
 */
public record SchemaChangeStatementFindingResponse(
        int statementIndex,
        UUID datasourceId,
        String ruleId,
        SqlReviewSeverity severity,
        Integer lineNumber,
        String message) {

    static SchemaChangeStatementFindingResponse from(SchemaChangeStatementFinding finding, String message) {
        var f = finding.finding();
        return new SchemaChangeStatementFindingResponse(finding.statementIndex(), finding.datasourceId(),
                f.ruleId(), f.severity(), f.lineNumber(), message);
    }
}
