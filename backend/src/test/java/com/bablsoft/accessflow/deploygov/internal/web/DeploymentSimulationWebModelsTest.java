package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentDecisionStepKind;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationResult;
import com.bablsoft.accessflow.workflow.api.QueryDecisionStepKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentSimulationWebModelsTest {

    private static final Instant AT = Instant.parse("2026-09-11T18:30:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    // ── SimulateDeploymentRequest ─────────────────────────────────────────────

    @Test
    void theRequestMapsOntoTheServiceInput() {
        var scheduled = AT.plusSeconds(3600);
        var body = new SimulateDeploymentRequest(userId, pipelineId, environmentId, "2.6.0",
                AiOutcome.COMPLETED, RiskLevel.HIGH, scheduled, AT);

        var input = body.toInput();

        assertThat(input.userId()).isEqualTo(userId);
        assertThat(input.pipelineId()).isEqualTo(pipelineId);
        assertThat(input.environmentId()).isEqualTo(environmentId);
        assertThat(input.version()).isEqualTo("2.6.0");
        assertThat(input.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(input.scheduledFor()).isEqualTo(scheduled);
        assertThat(input.at()).isEqualTo(AT);
    }

    @Test
    void anAbsentAiOutcomeDefaultsToSkippedAndAnAbsentInstantStaysNull() {
        // A null `at` is resolved to now by the service, not by the request — the request must not
        // stamp a clock it does not own.
        var input = new SimulateDeploymentRequest(userId, pipelineId, environmentId, "2.6.0", null,
                null, null, null).toInput();

        assertThat(input.aiOutcome()).isEqualTo(AiOutcome.SKIPPED);
        assertThat(input.at()).isNull();
        assertThat(input.scheduledFor()).isNull();
    }

    // ── DeploymentSimulationResponse ──────────────────────────────────────────

    @Test
    void theResponseResolvesEachReasonKeyAndEchoesTheGateVerdict() {
        var result = new DeploymentSimulationResult(List.of(
                new DecisionTraceStep(DeploymentDecisionStepKind.GATE_RELEASABILITY,
                        StepOutcome.DENY, "deploygov.simulation.gate.not_releasable", List.of(),
                        Map.of("frozen", true))),
                QueryStatus.PENDING_REVIEW, false, AT, List.of());

        var response = DeploymentSimulationResponse.from(result, (key, args) -> key + args);

        assertThat(response.releasable()).isFalse();
        assertThat(response.evaluatedAt()).isEqualTo(AT);
        assertThat(response.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(response.steps()).singleElement().satisfies(step -> {
            assertThat(step.step()).isEqualTo(DeploymentDecisionStepKind.GATE_RELEASABILITY);
            assertThat(step.outcome()).isEqualTo(StepOutcome.DENY);
            assertThat(step.reason()).isEqualTo("deploygov.simulation.gate.not_releasable[]");
            assertThat(step.details()).containsEntry("frozen", true);
        });
    }

    @Test
    void aBlockedTraceCarriesANullStatusSoTheWireOmitsIt() {
        var result = new DeploymentSimulationResult(List.of(), null, false, AT, List.of());

        assertThat(DeploymentSimulationResponse.from(result, (key, args) -> key).resultingStatus())
                .isNull();
    }

    @Test
    void aForeignStepKindIsLoudRatherThanSilentlyWidened() {
        var result = new DeploymentSimulationResult(List.of(
                DecisionTraceStep.of(QueryDecisionStepKind.MASKING, StepOutcome.ALLOW, "nope")),
                QueryStatus.APPROVED, true, AT, List.of());

        assertThatThrownBy(() -> DeploymentSimulationResponse.from(result, (key, args) -> key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MASKING");
    }
}
