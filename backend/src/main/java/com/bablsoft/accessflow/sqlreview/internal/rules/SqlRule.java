package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;

import java.util.List;
import java.util.Map;

/**
 * One deterministic SQL review rule (#862). A rule is a pure function of the parsed statement plus
 * its configured {@code params}: no schema introspection, no datasource connection, no clock.
 *
 * <p>Internal, not {@code api}: implementations walk the JSqlParser AST, which
 * {@code sqlreview.api} may never import. The findings a rule returns carry its
 * {@link #defaultSeverity()}; the evaluator re-stamps them with the severity the resolved ruleset
 * assigned. Messages are never rendered here — a finding carries {@code args} keyed by
 * {@link #messageArgKeys()}, and the reader-locale rendering maps those keys, in order, onto the
 * positional placeholders of {@code sqlreview.rule.<ruleId>.message}.
 */
public interface SqlRule {

    /** Stable, snake_case identifier — the {@code rule_id} persisted on configs and findings. */
    String ruleId();

    SqlRuleCategory category();

    /** The severity applied when the resolved ruleset has no config row for this rule. */
    SqlReviewSeverity defaultSeverity();

    /** The parameters this rule accepts; empty for the twelve parameterless rules. */
    default List<SqlRuleParam> params() {
        return List.of();
    }

    /**
     * The {@code args} keys of this rule's findings, in the order they bind to {@code {0}},
     * {@code {1}}… of {@code sqlreview.rule.<ruleId>.message}. Empty for an argument-less message.
     */
    default List<String> messageArgKeys() {
        return List.of();
    }

    /**
     * Evaluates one statement. {@code params} is the decoded config for this rule (possibly empty —
     * a rule with defaults applies them; one without yields nothing). Must not throw for any
     * parseable statement, but the evaluator tolerates a {@link RuntimeException} by skipping the
     * rule for that statement.
     */
    List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params);
}
