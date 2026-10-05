package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code LIKE '%…'} (also {@code ILIKE}, negated or not) — a leading wildcard is non-sargable and
 * forces a full scan of the column. Found anywhere in the statement, subqueries included; detection
 * lives in {@link LeadingWildcards}.
 */
public final class LeadingWildcardLikeRule implements SqlRule {

    public static final String ID = "leading_wildcard_like";

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.PERFORMANCE;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.WARN;
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("pattern");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var findings = new ArrayList<SqlReviewFinding>();
        for (LeadingWildcards.Match match : LeadingWildcards.find(StatementWalker.walk(context.statement()))) {
            findings.add(context.finding(this, match.like(), Map.of("pattern", match.pattern())));
        }
        return findings;
    }
}
