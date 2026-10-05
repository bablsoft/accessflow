package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleContext;
import com.bablsoft.accessflow.sqlreview.internal.rules.StatementFacts;
import net.sf.jsqlparser.parser.ASTNodeAccess;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An organization-defined rule (#1009) adapted to {@link SqlRule}, so the evaluator, the severity
 * resolution and the #864 BLOCK guard treat it exactly like a built-in. A matching statement yields
 * one finding whose {@code args.message} is the rule's own message with {@code {tables}},
 * {@code {functions}} and {@code {statement_type}} substituted — the message is snapshotted into the
 * finding, so history keeps rendering after the rule is edited or deleted.
 */
public final class CustomSqlRule implements SqlRule {

    public static final String ID_PREFIX = "custom_";
    public static final String MESSAGE_ARG = "message";

    /** Longest rendered message stored in a finding; long table lists are elided beyond it. */
    static final int RENDERED_MAX_LENGTH = 1000;

    private final String ruleId;
    private final String name;
    private final String description;
    private final SqlRuleCategory category;
    private final SqlReviewSeverity defaultSeverity;
    private final String message;
    private final SqlRuleConditionEvaluator evaluator;

    public CustomSqlRule(String ruleId, String name, String description, SqlRuleCategory category,
                         SqlReviewSeverity defaultSeverity, String message, SqlRuleCondition condition) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.category = Objects.requireNonNull(category, "category");
        this.defaultSeverity = Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        this.message = Objects.requireNonNull(message, "message");
        this.evaluator = new SqlRuleConditionEvaluator(Objects.requireNonNull(condition, "condition"));
    }

    /** Whether {@code ruleId} names a custom rule — decidable without a lookup. */
    public static boolean isCustomId(String ruleId) {
        return ruleId != null && ruleId.startsWith(ID_PREFIX);
    }

    @Override
    public String ruleId() {
        return ruleId;
    }

    /** The name the rule's author gave it — shown as is, never localized. */
    public String name() {
        return name;
    }

    /** The author's description, or {@code null}. */
    public String description() {
        return description;
    }

    @Override
    public SqlRuleCategory category() {
        return category;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return defaultSeverity;
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of(MESSAGE_ARG);
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var facts = context.facts();
        if (!evaluator.matches(facts)) {
            return List.of();
        }
        var anchor = context.statement() instanceof ASTNodeAccess node ? node : null;
        return List.of(context.finding(this, anchor, Map.of(MESSAGE_ARG, render(facts))));
    }

    String render(StatementFacts facts) {
        var rendered = message
                .replace("{tables}", String.join(", ", facts.tables()))
                .replace("{functions}", String.join(", ", facts.functions()))
                .replace("{statement_type}", facts.queryType().name());
        if (rendered.length() <= RENDERED_MAX_LENGTH) {
            return rendered;
        }
        int end = RENDERED_MAX_LENGTH - 1;
        if (Character.isHighSurrogate(rendered.charAt(end - 1))) {
            end--;
        }
        return rendered.substring(0, end) + "…";
    }
}
