package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionNodeTest {

    @Test
    void andNullChildrenBecomesEmptyImmutableList() {
        var node = new ConditionNode.And(null);
        assertThat(node.children()).isEmpty();
        assertThatThrownBy(() -> node.children().add(new ConditionNode.HasWhereClause(true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void orNullChildrenBecomesEmptyImmutableList() {
        assertThat(new ConditionNode.Or(null).children()).isEmpty();
    }

    @Test
    void notRejectsNullChild() {
        assertThatThrownBy(() -> new ConditionNode.Not(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notKeepsChild() {
        var child = new ConditionNode.HasWhereClause(true);
        assertThat(new ConditionNode.Not(child).child()).isEqualTo(child);
    }

    @Test
    void queryTypeInNullBecomesEmpty() {
        assertThat(new ConditionNode.QueryTypeIn(null).anyOf()).isEmpty();
        assertThat(new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)).anyOf())
                .containsExactly(QueryType.DELETE);
    }

    @Test
    void referencedTableMatchesNullBecomesEmpty() {
        assertThat(new ConditionNode.ReferencedTableMatches(null).globs()).isEmpty();
        assertThat(new ConditionNode.ReferencedTableMatches(List.of("a.*")).globs())
                .containsExactly("a.*");
    }

    @Test
    void riskLevelInNullBecomesEmpty() {
        assertThat(new ConditionNode.RiskLevelIn(null).anyOf()).isEmpty();
        assertThat(new ConditionNode.RiskLevelIn(Set.of(RiskLevel.HIGH)).anyOf())
                .containsExactly(RiskLevel.HIGH);
    }

    @Test
    void riskScoreRejectsNullOperator() {
        assertThatThrownBy(() -> new ConditionNode.RiskScore(null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void riskScoreKeepsOperatorAndValue() {
        var node = new ConditionNode.RiskScore(ComparisonOperator.GTE, 80);
        assertThat(node.operator()).isEqualTo(ComparisonOperator.GTE);
        assertThat(node.value()).isEqualTo(80);
    }

    @Test
    void requesterRoleInNullBecomesEmpty() {
        assertThat(new ConditionNode.RequesterRoleIn(null).anyOf()).isEmpty();
        assertThat(new ConditionNode.RequesterRoleIn(Set.of("ADMIN")).anyOf())
                .containsExactly("ADMIN");
    }

    @Test
    void requesterInGroupNullBecomesEmpty() {
        assertThat(new ConditionNode.RequesterInGroup(null).groupIds()).isEmpty();
        var id = UUID.randomUUID();
        assertThat(new ConditionNode.RequesterInGroup(Set.of(id)).groupIds()).containsExactly(id);
    }

    @Test
    void dayOfWeekInNullBecomesEmpty() {
        assertThat(new ConditionNode.DayOfWeekIn(null).anyOf()).isEmpty();
        assertThat(new ConditionNode.DayOfWeekIn(Set.of(DayOfWeek.MONDAY)).anyOf())
                .containsExactly(DayOfWeek.MONDAY);
    }

    @Test
    void timeOfDayRejectsOutOfRangeBounds() {
        assertThatThrownBy(() -> new ConditionNode.TimeOfDay(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConditionNode.TimeOfDay(1440, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConditionNode.TimeOfDay(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConditionNode.TimeOfDay(0, 1440))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeOfDayAcceptsBoundaryValues() {
        var node = new ConditionNode.TimeOfDay(0, 1439);
        assertThat(node.startMinuteOfDay()).isZero();
        assertThat(node.endMinuteOfDay()).isEqualTo(1439);
    }

    @Test
    void booleanLeavesExposeExpected() {
        assertThat(new ConditionNode.HasWhereClause(true).expected()).isTrue();
        assertThat(new ConditionNode.HasLimitClause(false).expected()).isFalse();
        assertThat(new ConditionNode.Transactional(true).expected()).isTrue();
    }

    @Test
    void sourceIpMatchesNullBecomesEmptyImmutableList() {
        assertThat(new ConditionNode.SourceIpMatches(null).cidrs()).isEmpty();
        assertThat(new ConditionNode.SourceIpMatches(List.of("10.0.0.0/8")).cidrs())
                .containsExactly("10.0.0.0/8");
        assertThatThrownBy(() -> new ConditionNode.SourceIpMatches(List.of("a")).cidrs().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void userAgentMatchesNullBecomesEmpty() {
        assertThat(new ConditionNode.UserAgentMatches(null).patterns()).isEmpty();
        assertThat(new ConditionNode.UserAgentMatches(List.of("*curl*")).patterns())
                .containsExactly("*curl*");
    }

    @Test
    void timeSinceLastApprovalRejectsNullOperator() {
        assertThatThrownBy(() -> new ConditionNode.TimeSinceLastApproval(null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeSinceLastApprovalRejectsNegativeMinutes() {
        assertThatThrownBy(() -> new ConditionNode.TimeSinceLastApproval(ComparisonOperator.GT, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeSinceLastApprovalKeepsOperatorAndMinutes() {
        var node = new ConditionNode.TimeSinceLastApproval(ComparisonOperator.GT, 1440);
        assertThat(node.operator()).isEqualTo(ComparisonOperator.GT);
        assertThat(node.minutes()).isEqualTo(1440);
    }

    @Test
    void ciCdOriginExposesExpected() {
        assertThat(new ConditionNode.CiCdOrigin(true).expected()).isTrue();
        assertThat(new ConditionNode.CiCdOrigin(false).expected()).isFalse();
    }
}
