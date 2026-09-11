package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure evaluation of parsed statements against resolved rules (#862) — no Spring, no
 * repositories, no clock. {@code OFF} rules are never applied. A rule that throws on a statement
 * is skipped for that statement and logged; the query is never made harder to approve by a rule
 * bug. Findings are re-stamped with the resolved severity and ordered by statement index, then
 * line (unknown lines last), then rule id, so the output is stable.
 */
public final class SqlReviewEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SqlReviewEvaluator.class);

    private static final Comparator<SqlReviewFinding> ORDER = Comparator
            .comparingInt(SqlReviewFinding::statementIndex)
            .thenComparing(SqlReviewFinding::lineNumber, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SqlReviewFinding::ruleId);

    public SqlReviewResult evaluate(List<SqlRuleContext> statements, List<ResolvedRule> rules) {
        var findings = new ArrayList<SqlReviewFinding>();
        for (ResolvedRule resolved : rules) {
            if (resolved.severity() == SqlReviewSeverity.OFF) {
                continue;
            }
            for (SqlRuleContext statement : statements) {
                findings.addAll(applySafely(resolved, statement));
            }
        }
        findings.sort(ORDER);
        return new SqlReviewResult(true, findings);
    }

    private static List<SqlReviewFinding> applySafely(ResolvedRule resolved, SqlRuleContext statement) {
        List<SqlReviewFinding> raw;
        try {
            raw = resolved.rule().apply(statement, resolved.params());
        } catch (RuntimeException ex) {
            log.warn("SQL review rule {} failed on statement {}; skipping it for this statement",
                    resolved.rule().ruleId(), statement.statementIndex(), ex);
            return List.of();
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var stamped = new ArrayList<SqlReviewFinding>(raw.size());
        for (SqlReviewFinding finding : raw) {
            stamped.add(new SqlReviewFinding(finding.ruleId(), resolved.severity(), finding.statementIndex(),
                    finding.lineNumber(), finding.args()));
        }
        return stamped;
    }
}
