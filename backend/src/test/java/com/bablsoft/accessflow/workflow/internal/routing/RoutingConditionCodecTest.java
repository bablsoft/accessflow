package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.workflow.api.ComparisonOperator;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingConditionCodecTest {

    private final RoutingConditionCodec codec = new RoutingConditionCodec(
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
            messageSource());

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @Test
    void roundTripsEveryLeafAndCombinator() {
        ConditionNode tree = new ConditionNode.And(List.of(
                new ConditionNode.Or(List.of(
                        new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE, QueryType.UPDATE)),
                        new ConditionNode.Not(new ConditionNode.HasWhereClause(true)))),
                new ConditionNode.ReferencedTableMatches(List.of("payroll.*")),
                new ConditionNode.RiskLevelIn(Set.of(RiskLevel.HIGH)),
                new ConditionNode.RiskScore(ComparisonOperator.GTE, 80),
                new ConditionNode.RequesterRoleIn(Set.of(UserRoleType.ANALYST)),
                new ConditionNode.RequesterInGroup(Set.of(UUID.randomUUID())),
                new ConditionNode.TimeOfDay(1320, 360),
                new ConditionNode.DayOfWeekIn(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
                new ConditionNode.HasLimitClause(false),
                new ConditionNode.Transactional(true)));

        var json = codec.encode(tree);
        var decoded = codec.decode(json);

        assertThat(decoded).isEqualTo(tree);
    }

    @Test
    void wireShapeUsesTypeDiscriminator() {
        var json = codec.encode(new ConditionNode.RiskScore(ComparisonOperator.GTE, 80));
        assertThat(json).contains("\"type\":\"risk_score\"").contains("\"value\":80");
    }

    @Test
    void decodeRejectsBlankJson() {
        assertThatThrownBy(() -> codec.decode("  "))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void decodeRejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{\"type\":\"nope\"}"))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void fromJsonRejectsEmptyTree() {
        var empty = JsonMapper.builder().build().createObjectNode();
        assertThatThrownBy(() -> codec.fromJson(empty))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void toJsonAndFromJsonRoundTrip() {
        ConditionNode node = new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT));
        var json = codec.toJson(node);
        assertThat(codec.fromJson(json)).isEqualTo(node);
    }
}
