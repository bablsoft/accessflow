package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Pure, stateless evaluator of a {@link ConditionNode} tree against a {@link ConditionContext}.
 * The recursive {@code switch} is exhaustive over the sealed type (no {@code default}), so adding a
 * new condition variant is a compile error until handled here. Never throws on a missing signal:
 * risk-based leaves return {@code false} when the context carries no AI risk signal (skip path).
 */
@Component
public class RoutingConditionEvaluator {

    public boolean matches(ConditionNode node, ConditionContext ctx) {
        return switch (node) {
            case ConditionNode.And and ->
                    and.children().stream().allMatch(child -> matches(child, ctx));
            case ConditionNode.Or or ->
                    or.children().stream().anyMatch(child -> matches(child, ctx));
            case ConditionNode.Not not -> !matches(not.child(), ctx);
            case ConditionNode.QueryTypeIn c -> c.anyOf().contains(ctx.queryType());
            case ConditionNode.ReferencedTableMatches c -> matchesAnyTable(c, ctx);
            case ConditionNode.RiskLevelIn c ->
                    ctx.hasRiskSignal() && c.anyOf().contains(ctx.riskLevel());
            case ConditionNode.RiskScore c ->
                    ctx.hasRiskSignal() && c.operator().test(ctx.riskScore(), c.value());
            case ConditionNode.RequesterRoleIn c -> c.anyOf().contains(ctx.requesterRole());
            case ConditionNode.RequesterInGroup c ->
                    !Collections.disjoint(c.groupIds(), ctx.requesterGroupIds());
            case ConditionNode.TimeOfDay c -> matchesTimeOfDay(c, ctx);
            case ConditionNode.DayOfWeekIn c ->
                    c.anyOf().contains(ctx.evaluatedAt().getDayOfWeek());
            case ConditionNode.HasWhereClause c -> ctx.hasWhereClause() == c.expected();
            case ConditionNode.HasLimitClause c -> ctx.hasLimitClause() == c.expected();
            case ConditionNode.Transactional c -> ctx.transactional() == c.expected();
        };
    }

    private static boolean matchesAnyTable(ConditionNode.ReferencedTableMatches c,
                                           ConditionContext ctx) {
        return c.globs().stream()
                .anyMatch(glob -> ctx.referencedTables().stream()
                        .anyMatch(table -> GlobMatcher.matches(glob, table)));
    }

    private static boolean matchesTimeOfDay(ConditionNode.TimeOfDay c, ConditionContext ctx) {
        int minuteOfDay = ctx.evaluatedAt().getHour() * 60 + ctx.evaluatedAt().getMinute();
        if (c.startMinuteOfDay() <= c.endMinuteOfDay()) {
            return minuteOfDay >= c.startMinuteOfDay() && minuteOfDay <= c.endMinuteOfDay();
        }
        // Wrap-around window (e.g. 22:00–06:00): match either side of midnight.
        return minuteOfDay >= c.startMinuteOfDay() || minuteOfDay <= c.endMinuteOfDay();
    }
}
