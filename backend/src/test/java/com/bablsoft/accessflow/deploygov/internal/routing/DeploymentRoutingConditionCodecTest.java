package com.bablsoft.accessflow.deploygov.internal.routing;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentRoutingConditionCodecTest {

    private final DeploymentRoutingConditionCodec codec =
            new DeploymentRoutingConditionCodec(JsonMapper.builder().build());

    @Test
    void roundTripsEveryLeaf() {
        var conditions = new DeploymentRoutingConditions(List.of("production"),
                List.of("GITHUB_ACTIONS"), RiskLevel.HIGH, List.of("2.*"), Set.of(5, 6),
                LocalTime.of(16, 0), LocalTime.of(23, 0), "Europe/Berlin");

        var decoded = codec.fromJson(codec.toJson(conditions));

        assertThat(decoded).isEqualTo(conditions);
    }

    @Test
    void blankOrEmptyBlobIsUnconstrained() {
        assertThat(codec.fromJson(null)).isEqualTo(DeploymentRoutingConditions.NONE);
        assertThat(codec.fromJson("  ")).isEqualTo(DeploymentRoutingConditions.NONE);
        assertThat(codec.fromJson("{}")).isEqualTo(DeploymentRoutingConditions.NONE);
        assertThat(codec.fromJson("null")).isEqualTo(DeploymentRoutingConditions.NONE);
    }

    @Test
    void emptyConditionsSerializeToAnEmptyObject() {
        assertThat(codec.toJson(DeploymentRoutingConditions.NONE)).isEqualTo("{}");
        assertThat(codec.toJson(null)).isEqualTo("{}");
    }

    @Test
    void unknownKeysAreTolerated() {
        var decoded = codec.fromJson("{\"environments\":[\"prod\"],\"somethingNew\":42}");

        assertThat(decoded.environments()).containsExactly("prod");
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> codec.fromJson("{not json"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
    }

    @Test
    void nonObjectRootIsRejected() {
        assertThatThrownBy(() -> codec.fromJson("[1,2,3]"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void arrayLeavesMustHoldNonBlankStrings() {
        assertThatThrownBy(() -> codec.fromJson("{\"environments\":\"prod\"}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
        assertThatThrownBy(() -> codec.fromJson("{\"providers\":[\"\"]}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
    }

    @Test
    void daysMustBeIsoNumbers() {
        assertThatThrownBy(() -> codec.fromJson("{\"daysOfWeek\":[0]}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
        assertThatThrownBy(() -> codec.fromJson("{\"daysOfWeek\":[8]}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
        assertThatThrownBy(() -> codec.fromJson("{\"daysOfWeek\":\"monday\"}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
        assertThat(codec.fromJson("{\"daysOfWeek\":[1,7]}").daysOfWeek()).containsExactlyInAnyOrder(1, 7);
    }

    @Test
    void riskLevelMustBeKnown() {
        assertThat(codec.fromJson("{\"minRiskLevel\":\"high\"}").minRiskLevel())
                .isEqualTo(RiskLevel.HIGH);
        assertThatThrownBy(() -> codec.fromJson("{\"minRiskLevel\":\"EXTREME\"}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
    }

    @Test
    void timesMustBeWallClock() {
        assertThat(codec.fromJson("{\"startTime\":\"09:00\"}").startTime())
                .isEqualTo(LocalTime.of(9, 0));
        assertThatThrownBy(() -> codec.fromJson("{\"endTime\":\"25:00\"}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
        assertThatThrownBy(() -> codec.fromJson("{\"timezone\":\"\"}"))
                .isInstanceOf(DeploymentRoutingConditionCodec.ConditionsParseException.class);
    }
}
