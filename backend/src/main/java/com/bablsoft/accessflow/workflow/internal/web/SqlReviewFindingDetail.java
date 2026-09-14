package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;

import java.util.List;
import java.util.function.Function;

/**
 * One SQL review finding as a reviewer sees it (#864) — the same wire shape as
 * {@code POST /sql-review/evaluate}, rendered into the caller's locale at read time; the stored row
 * carries only {@code rule_id} + {@code args}.
 */
public record SqlReviewFindingDetail(
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

    static List<SqlReviewFindingDetail> from(List<SqlReviewFinding> findings,
                                             Function<SqlReviewFinding, String> render) {
        return findings == null ? List.of()
                : findings.stream().map(finding -> from(finding, render)).toList();
    }
}
