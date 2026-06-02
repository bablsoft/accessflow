package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.workflow.api.ComparisonOperator;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingConditionEvaluatorTest {

    private final RoutingConditionEvaluator evaluator = new RoutingConditionEvaluator();

    // A Wednesday at 14:30 with a HIGH/82 risk DELETE on payroll.salaries, no WHERE, no LIMIT.
    private final UUID groupId = UUID.randomUUID();

    private ConditionContext context() {
        return new ConditionContext(
                QueryType.DELETE,
                Set.of("payroll.salaries"),
                RiskLevel.HIGH,
                82,
                UserRoleType.ANALYST,
                Set.of(groupId),
                LocalDateTime.of(2026, 6, 3, 14, 30), // Wednesday
                false,
                false,
                false);
    }

    private ConditionContext noRiskContext() {
        return new ConditionContext(QueryType.DELETE, Set.of("payroll.salaries"), null, -1,
                UserRoleType.ANALYST, Set.of(groupId), LocalDateTime.of(2026, 6, 3, 14, 30),
                false, false, false);
    }

    @Test
    void queryType() {
        assertThat(evaluator.matches(new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)),
                context())).isFalse();
    }

    @Test
    void referencedTableGlob() {
        assertThat(evaluator.matches(
                new ConditionNode.ReferencedTableMatches(List.of("payroll.*")), context())).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.ReferencedTableMatches(List.of("hr.*")), context())).isFalse();
    }

    @Test
    void riskLevel() {
        assertThat(evaluator.matches(
                new ConditionNode.RiskLevelIn(Set.of(RiskLevel.HIGH, RiskLevel.CRITICAL)),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.RiskLevelIn(Set.of(RiskLevel.LOW)),
                context())).isFalse();
    }

    @Test
    void riskLevelFalseWhenNoSignal() {
        assertThat(evaluator.matches(new ConditionNode.RiskLevelIn(Set.of(RiskLevel.HIGH)),
                noRiskContext())).isFalse();
    }

    @Test
    void riskScoreEachOperator() {
        assertThat(evaluator.matches(new ConditionNode.RiskScore(ComparisonOperator.GTE, 80),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.RiskScore(ComparisonOperator.GT, 82),
                context())).isFalse();
        assertThat(evaluator.matches(new ConditionNode.RiskScore(ComparisonOperator.LT, 90),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.RiskScore(ComparisonOperator.LTE, 82),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.RiskScore(ComparisonOperator.EQ, 82),
                context())).isTrue();
    }

    @Test
    void riskScoreFalseWhenNoSignal() {
        assertThat(evaluator.matches(new ConditionNode.RiskScore(ComparisonOperator.GTE, 0),
                noRiskContext())).isFalse();
    }

    @Test
    void requesterRole() {
        assertThat(evaluator.matches(
                new ConditionNode.RequesterRoleIn(Set.of(UserRoleType.ANALYST)), context())).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.RequesterRoleIn(Set.of(UserRoleType.ADMIN)), context())).isFalse();
    }

    @Test
    void requesterGroupIntersection() {
        assertThat(evaluator.matches(new ConditionNode.RequesterInGroup(Set.of(groupId)),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.RequesterInGroup(Set.of(UUID.randomUUID())),
                context())).isFalse();
    }

    @Test
    void timeOfDayWindow() {
        assertThat(evaluator.matches(new ConditionNode.TimeOfDay(13 * 60, 15 * 60),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.TimeOfDay(15 * 60, 16 * 60),
                context())).isFalse();
    }

    @Test
    void timeOfDayWrapAroundOvernightWindow() {
        // 22:00–06:00 after-hours window; 14:30 is outside, 23:00 / 02:00 are inside.
        var afterHours = new ConditionNode.TimeOfDay(22 * 60, 6 * 60);
        assertThat(evaluator.matches(afterHours, context())).isFalse();
        assertThat(evaluator.matches(afterHours, at(23, 0))).isTrue();
        assertThat(evaluator.matches(afterHours, at(2, 0))).isTrue();
    }

    @Test
    void dayOfWeek() {
        assertThat(evaluator.matches(new ConditionNode.DayOfWeekIn(Set.of(DayOfWeek.WEDNESDAY)),
                context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.DayOfWeekIn(Set.of(DayOfWeek.MONDAY)),
                context())).isFalse();
    }

    @Test
    void hasWhereClause() {
        assertThat(evaluator.matches(new ConditionNode.HasWhereClause(false), context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.HasWhereClause(true), context())).isFalse();
    }

    @Test
    void hasLimitClause() {
        assertThat(evaluator.matches(new ConditionNode.HasLimitClause(false), context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.HasLimitClause(true), context())).isFalse();
    }

    @Test
    void transactionalFlag() {
        assertThat(evaluator.matches(new ConditionNode.Transactional(false), context())).isTrue();
        assertThat(evaluator.matches(new ConditionNode.Transactional(true), context())).isFalse();
    }

    @Test
    void andRequiresAllChildren() {
        var node = new ConditionNode.And(List.of(
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)),
                new ConditionNode.HasWhereClause(false)));
        assertThat(evaluator.matches(node, context())).isTrue();
        var failing = new ConditionNode.And(List.of(
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)),
                new ConditionNode.HasWhereClause(true)));
        assertThat(evaluator.matches(failing, context())).isFalse();
    }

    @Test
    void orRequiresAnyChild() {
        var node = new ConditionNode.Or(List.of(
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)),
                new ConditionNode.RiskLevelIn(Set.of(RiskLevel.HIGH))));
        assertThat(evaluator.matches(node, context())).isTrue();
        var failing = new ConditionNode.Or(List.of(
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)),
                new ConditionNode.RiskLevelIn(Set.of(RiskLevel.LOW))));
        assertThat(evaluator.matches(failing, context())).isFalse();
    }

    @Test
    void notNegatesChild() {
        assertThat(evaluator.matches(
                new ConditionNode.Not(new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT))),
                context())).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.Not(new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE))),
                context())).isFalse();
    }

    private ConditionContext at(int hour, int minute) {
        return new ConditionContext(QueryType.DELETE, Set.of("payroll.salaries"), RiskLevel.HIGH, 82,
                UserRoleType.ANALYST, Set.of(groupId), LocalDateTime.of(2026, 6, 3, hour, minute),
                false, false, false);
    }
}
