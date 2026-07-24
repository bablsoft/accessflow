package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
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
                "ANALYST",
                Set.of(groupId),
                LocalDateTime.of(2026, 6, 3, 14, 30), // Wednesday
                false,
                false,
                false,
                "203.0.113.7",
                "Mozilla/5.0 (Macintosh)",
                false,
                120,
                false);
    }

    private ConditionContext noRiskContext() {
        return new ConditionContext(QueryType.DELETE, Set.of("payroll.salaries"), null, -1,
                "ANALYST", Set.of(groupId), LocalDateTime.of(2026, 6, 3, 14, 30),
                false, false, false, "203.0.113.7", "Mozilla/5.0 (Macintosh)", false, 120, false);
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
                new ConditionNode.RequesterRoleIn(Set.of("ANALYST")), context())).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.RequesterRoleIn(Set.of("ADMIN")), context())).isFalse();
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
                "ANALYST", Set.of(groupId), LocalDateTime.of(2026, 6, 3, hour, minute),
                false, false, false, "203.0.113.7", "Mozilla/5.0 (Macintosh)", false, 120, false);
    }

    // --- Client-context conditions (AF-446) ---

    private ConditionContext clientContext(String ip, String userAgent, boolean ciCdOrigin,
                                           Integer minutesSinceLastApproval) {
        return new ConditionContext(QueryType.DELETE, Set.of("payroll.salaries"), RiskLevel.HIGH, 82,
                "ANALYST", Set.of(groupId), LocalDateTime.of(2026, 6, 3, 14, 30),
                false, false, false, ip, userAgent, ciCdOrigin, minutesSinceLastApproval, false);
    }

    @Test
    void sourceIpMatchesCidr() {
        var corporate = new ConditionNode.SourceIpMatches(List.of("203.0.113.0/24", "10.0.0.0/8"));
        assertThat(evaluator.matches(corporate, clientContext("203.0.113.7", null, false, null)))
                .isTrue();
        assertThat(evaluator.matches(corporate, clientContext("198.51.100.4", null, false, null)))
                .isFalse();
    }

    @Test
    void sourceIpMatchesIpv6Cidr() {
        var node = new ConditionNode.SourceIpMatches(List.of("2001:db8::/32"));
        assertThat(evaluator.matches(node, clientContext("2001:db8::1", null, false, null))).isTrue();
        assertThat(evaluator.matches(node, clientContext("2001:dead::1", null, false, null)))
                .isFalse();
    }

    @Test
    void sourceIpFailsClosedWhenIpMissing() {
        var corporate = new ConditionNode.SourceIpMatches(List.of("203.0.113.0/24"));
        assertThat(evaluator.matches(corporate, clientContext(null, null, false, null))).isFalse();
        // Not(SourceIpMatches) stays true on missing IP, so escalation still fires.
        assertThat(evaluator.matches(new ConditionNode.Not(corporate),
                clientContext(null, null, false, null))).isTrue();
    }

    @Test
    void userAgentGlob() {
        var node = new ConditionNode.UserAgentMatches(List.of("*curl*", "*GitHubActions*"));
        assertThat(evaluator.matches(node, clientContext("203.0.113.7", "curl/8.4.0", false, null)))
                .isTrue();
        assertThat(evaluator.matches(node,
                clientContext("203.0.113.7", "Mozilla/5.0 (Macintosh)", false, null))).isFalse();
    }

    @Test
    void userAgentFailsClosedWhenMissing() {
        var node = new ConditionNode.UserAgentMatches(List.of("*curl*"));
        assertThat(evaluator.matches(node, clientContext("203.0.113.7", null, false, null)))
                .isFalse();
    }

    @Test
    void timeSinceLastApprovalEachOperator() {
        var ctx = clientContext("203.0.113.7", null, false, 120);
        assertThat(evaluator.matches(
                new ConditionNode.TimeSinceLastApproval(ComparisonOperator.GT, 60), ctx)).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.TimeSinceLastApproval(ComparisonOperator.LT, 60), ctx)).isFalse();
        assertThat(evaluator.matches(
                new ConditionNode.TimeSinceLastApproval(ComparisonOperator.EQ, 120), ctx)).isTrue();
    }

    @Test
    void timeSinceLastApprovalFailsClosedWhenNoPriorApproval() {
        var ctx = clientContext("203.0.113.7", null, false, null);
        assertThat(evaluator.matches(
                new ConditionNode.TimeSinceLastApproval(ComparisonOperator.GTE, 0), ctx)).isFalse();
    }

    @Test
    void anomalyDetected() {
        assertThat(evaluator.matches(new ConditionNode.AnomalyDetected(true),
                anomalyContext(true))).isTrue();
        assertThat(evaluator.matches(new ConditionNode.AnomalyDetected(true),
                anomalyContext(false))).isFalse();
        assertThat(evaluator.matches(new ConditionNode.AnomalyDetected(false),
                anomalyContext(false))).isTrue();
    }

    private ConditionContext anomalyContext(boolean anomalyActive) {
        return new ConditionContext(QueryType.DELETE, Set.of("payroll.salaries"), RiskLevel.HIGH, 82,
                "ANALYST", Set.of(groupId), LocalDateTime.of(2026, 6, 3, 14, 30),
                false, false, false, "203.0.113.7", "Mozilla/5.0 (Macintosh)", false, 120,
                anomalyActive);
    }

    @Test
    void ciCdOrigin() {
        assertThat(evaluator.matches(new ConditionNode.CiCdOrigin(true),
                clientContext("203.0.113.7", null, true, null))).isTrue();
        assertThat(evaluator.matches(new ConditionNode.CiCdOrigin(true),
                clientContext("203.0.113.7", null, false, null))).isFalse();
        assertThat(evaluator.matches(new ConditionNode.CiCdOrigin(false),
                clientContext("203.0.113.7", null, false, null))).isTrue();
    }

    @Test
    void estimatedRows() {
        var ctx = estimateContext(2_400_000L, "Seq Scan");
        assertThat(evaluator.matches(
                new ConditionNode.EstimatedRows(ComparisonOperator.GT, 100_000), ctx)).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.EstimatedRows(ComparisonOperator.LT, 100_000), ctx)).isFalse();
        assertThat(evaluator.matches(
                new ConditionNode.EstimatedRows(ComparisonOperator.EQ, 2_400_000), ctx)).isTrue();
    }

    @Test
    void estimatedRowsFailsClosedWhenNoEstimate() {
        var ctx = estimateContext(null, null);
        assertThat(evaluator.matches(
                new ConditionNode.EstimatedRows(ComparisonOperator.GTE, 0), ctx)).isFalse();
    }

    @Test
    void scanType() {
        var ctx = estimateContext(500L, "Seq Scan");
        assertThat(evaluator.matches(
                new ConditionNode.ScanTypeMatches(List.of("Seq*")), ctx)).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.ScanTypeMatches(List.of("seq scan")), ctx)).isTrue();
        assertThat(evaluator.matches(
                new ConditionNode.ScanTypeMatches(List.of("Index*")), ctx)).isFalse();
    }

    @Test
    void scanTypeFailsClosedWhenNoPlan() {
        var ctx = estimateContext(500L, null);
        assertThat(evaluator.matches(
                new ConditionNode.ScanTypeMatches(List.of("*")), ctx)).isFalse();
    }

    private ConditionContext estimateContext(Long estimatedRows, String scanType) {
        return new ConditionContext(QueryType.DELETE, Set.of("payroll.salaries"), RiskLevel.HIGH, 82,
                "ANALYST", Set.of(groupId), LocalDateTime.of(2026, 6, 3, 14, 30),
                false, false, false, "203.0.113.7", "Mozilla/5.0 (Macintosh)", false, 120,
                false, estimatedRows, scanType);
    }
}
