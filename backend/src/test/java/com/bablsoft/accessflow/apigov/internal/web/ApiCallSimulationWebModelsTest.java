package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiCallSimulationResult;
import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.workflow.api.QueryDecisionStepKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCallSimulationWebModelsTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    // ── SimulateApiCallRequest ────────────────────────────────────────────────

    @Test
    void theRequestMapsOntoTheServiceInput() {
        var body = new SimulateApiCallRequest(userId, connectorId, "deleteCustomer", "DELETE",
                AiOutcome.COMPLETED, RiskLevel.HIGH);

        var input = body.toInput();

        assertThat(input.userId()).isEqualTo(userId);
        assertThat(input.connectorId()).isEqualTo(connectorId);
        assertThat(input.operationId()).isEqualTo("deleteCustomer");
        assertThat(input.verb()).isEqualTo("DELETE");
        assertThat(input.aiOutcome()).isEqualTo(AiOutcome.COMPLETED);
        assertThat(input.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void blankOperationAndVerbBecomeNullSoTheyReadAsAFreeFormCall() {
        var input = new SimulateApiCallRequest(userId, connectorId, "   ", "", null, null).toInput();

        assertThat(input.operationId()).isNull();
        assertThat(input.verb()).isNull();
    }

    @Test
    void anAbsentAiOutcomeDefaultsToSkipped() {
        assertThat(new SimulateApiCallRequest(userId, connectorId, null, null, null, null)
                .toInput().aiOutcome()).isEqualTo(AiOutcome.SKIPPED);
    }

    // ── ApiCallSimulationResponse ─────────────────────────────────────────────

    @Test
    void theResponseResolvesEachReasonKeyThroughTheSuppliedResolver() {
        var result = new ApiCallSimulationResult(List.of(
                new DecisionTraceStep(ApiDecisionStepKind.ROUTING_POLICIES, StepOutcome.MATCH,
                        "apigov.decision.routing.matched", List.of("the policy", "ESCALATE"),
                        Map.of("action", "ESCALATE"))),
                QueryStatus.PENDING_REVIEW, List.of(SimulationCaveat.RESPONSE_SHAPE_ABSENT));

        var response = ApiCallSimulationResponse.from(result,
                (key, args) -> key + args);

        assertThat(response.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(response.caveats()).containsExactly(SimulationCaveat.RESPONSE_SHAPE_ABSENT);
        assertThat(response.steps()).singleElement().satisfies(step -> {
            assertThat(step.step()).isEqualTo(ApiDecisionStepKind.ROUTING_POLICIES);
            assertThat(step.outcome()).isEqualTo(StepOutcome.MATCH);
            assertThat(step.reason())
                    .isEqualTo("apigov.decision.routing.matched[the policy, ESCALATE]");
            assertThat(step.details()).containsEntry("action", "ESCALATE");
        });
    }

    @Test
    void aBlockedTraceCarriesANullStatusSoTheWireOmitsIt() {
        var result = new ApiCallSimulationResult(List.of(), null, List.of());

        assertThat(ApiCallSimulationResponse.from(result, (key, args) -> key).resultingStatus())
                .isNull();
    }

    @Test
    void aForeignStepKindIsLoudRatherThanSilentlyWidened() {
        // Unreachable in practice, but the narrowing is what keeps the OpenAPI schema an enum, so
        // it must fail visibly rather than degrade to a bare string.
        var result = new ApiCallSimulationResult(List.of(
                DecisionTraceStep.of(QueryDecisionStepKind.SQL_PARSE, StepOutcome.ALLOW, "nope")),
                QueryStatus.APPROVED, List.of());

        assertThatThrownBy(() -> ApiCallSimulationResponse.from(result, (key, args) -> key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SQL_PARSE");
    }
}
