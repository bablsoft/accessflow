package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.workflow.api.AccessSimulationResult;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.DecisionStepKind;
import com.bablsoft.accessflow.workflow.api.DecisionTraceStep;
import com.bablsoft.accessflow.workflow.api.StepOutcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessSimulationResponseTest {

    @Test
    void resolvesEachStepsMessageKeyWithItsArguments() {
        var result = new AccessSimulationResult(
                List.of(new DecisionTraceStep(DecisionStepKind.ROUTING_POLICIES, StepOutcome.MATCH,
                        "routing.matched", List.of("Escalate writes", "ESCALATE"),
                        Map.of("matched_policy_id", "p1"))),
                QueryStatus.PENDING_REVIEW, null, List.of(SimulationCaveat.CLIENT_CONTEXT_ABSENT));

        var response = AccessSimulationResponse.from(result,
                (key, args) -> key + "|" + String.join(",", args));

        assertThat(response.steps()).hasSize(1);
        assertThat(response.steps().get(0).reason())
                .isEqualTo("routing.matched|Escalate writes,ESCALATE");
        assertThat(response.steps().get(0).details()).containsEntry("matched_policy_id", "p1");
        assertThat(response.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(response.caveats()).containsExactly(SimulationCaveat.CLIENT_CONTEXT_ABSENT);
    }

    @Test
    void anAbsentContextStaysAbsentRatherThanBecomingAnEmptyObject() {
        var result = new AccessSimulationResult(List.of(), null, null, List.of());

        var response = AccessSimulationResponse.from(result, (key, args) -> key);

        assertThat(response.evaluatedContext()).isNull();
        assertThat(response.resultingStatus()).isNull();
    }

    @Test
    void echoesEveryRoutingSignalTheEvaluationSaw() {
        var groupId = UUID.randomUUID();
        var evaluatedAt = LocalDateTime.parse("2026-09-10T11:04:00");
        var context = new ConditionContext(QueryType.UPDATE, Set.of("public.payments"),
                RiskLevel.HIGH, 82, "ANALYST", Set.of(groupId), evaluatedAt, true, false, false,
                null, null, false, 47, false, null, null);

        var response = AccessSimulationResponse.from(
                new AccessSimulationResult(List.of(), QueryStatus.PENDING_REVIEW, context,
                        List.of()),
                (key, args) -> key);

        var echoed = response.evaluatedContext();
        assertThat(echoed.queryType()).isEqualTo(QueryType.UPDATE);
        assertThat(echoed.referencedTables()).containsExactly("public.payments");
        assertThat(echoed.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(echoed.riskScore()).isEqualTo(82);
        assertThat(echoed.requesterGroupIds()).containsExactly(groupId);
        assertThat(echoed.evaluatedAt()).isEqualTo(evaluatedAt);
        assertThat(echoed.minutesSinceLastApproval()).isEqualTo(47);
        // A hypothetical request carries no client context; the caveat says so, the field stays null.
        assertThat(echoed.requesterIpAddress()).isNull();
        assertThat(echoed.estimatedRows()).isNull();
    }
}
