package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;

import java.util.function.Function;

/**
 * One SQL review finding on a request-group member (#864) — the wire shape of
 * {@code POST /sql-review/evaluate} and the query detail, rendered into the caller's locale.
 */
record SqlReviewFindingDetail(
        String ruleId,
        SqlReviewSeverity severity,
        int statementIndex,
        Integer lineNumber,
        String message) {

    static SqlReviewFindingDetail from(SqlReviewFinding finding,
                                       Function<SqlReviewFinding, String> render) {
        return new SqlReviewFindingDetail(finding.ruleId(), finding.severity(),
                finding.statementIndex(), finding.lineNumber(), render.apply(finding));
    }
}
