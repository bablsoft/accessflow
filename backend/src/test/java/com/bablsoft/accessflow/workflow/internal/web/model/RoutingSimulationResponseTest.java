package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationOutcome;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingSimulationResponseTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void mapsAnEmptyResultWithoutNulls() {
        var response = RoutingSimulationResponse.from(new RoutingSimulationResult(
                FROM, TO, null, 0, 0, false, null, null, null, null));

        assertThat(response.outcomeDeltas()).isEmpty();
        assertThat(response.userImpacts()).isEmpty();
        assertThat(response.samples()).isEmpty();
        assertThat(response.caveats()).isEmpty();
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void mapsCountsWindowAndCaveats() {
        var datasourceId = UUID.randomUUID();
        var response = RoutingSimulationResponse.from(new RoutingSimulationResult(
                FROM, TO, datasourceId, 1284, 312, true, List.of(), List.of(), List.of(),
                List.of(SimulationCaveat.MEMBERSHIP_STATE_CURRENT)));

        assertThat(response.periodFrom()).isEqualTo(FROM);
        assertThat(response.periodTo()).isEqualTo(TO);
        assertThat(response.datasourceId()).isEqualTo(datasourceId);
        assertThat(response.evaluatedCount()).isEqualTo(1284);
        assertThat(response.changedCount()).isEqualTo(312);
        assertThat(response.truncated()).isTrue();
        assertThat(response.caveats()).containsExactly(SimulationCaveat.MEMBERSHIP_STATE_CURRENT);
    }

    @Test
    void mapsDeltasAndUserImpacts() {
        var userId = UUID.randomUUID();
        var response = RoutingSimulationResponse.from(new RoutingSimulationResult(
                FROM, TO, null, 10, 4, false,
                List.of(new RoutingSimulationResult.OutcomeDelta(RoutingSimulationOutcome.NO_MATCH,
                        RoutingSimulationOutcome.AUTO_REJECT, 4)),
                List.of(new RoutingSimulationResult.UserImpact(userId, "a@x.io", "Ada", 4,
                        List.of(RoutingSimulationOutcome.AUTO_REJECT))),
                List.of(), List.of()));

        assertThat(response.outcomeDeltas()).singleElement().satisfies(delta -> {
            assertThat(delta.baselineAction()).isEqualTo(RoutingSimulationOutcome.NO_MATCH);
            assertThat(delta.simulatedAction()).isEqualTo(RoutingSimulationOutcome.AUTO_REJECT);
            assertThat(delta.count()).isEqualTo(4);
        });
        assertThat(response.userImpacts()).singleElement().satisfies(impact -> {
            assertThat(impact.userId()).isEqualTo(userId);
            assertThat(impact.email()).isEqualTo("a@x.io");
            assertThat(impact.changedCount()).isEqualTo(4);
            assertThat(impact.simulatedActions())
                    .containsExactly(RoutingSimulationOutcome.AUTO_REJECT);
        });
    }

    @Test
    void aNullBaselineMatchStaysNullMeaningNoPolicyMatchedOnThatArm() {
        var queryId = UUID.randomUUID();
        var response = RoutingSimulationResponse.from(new RoutingSimulationResult(
                FROM, TO, null, 1, 1, false, List.of(), List.of(),
                List.of(new RoutingSimulationResult.Sample(queryId, "a@x.io", "prod",
                        QueryType.DELETE, QueryStatus.EXECUTED, FROM, null,
                        new RoutingSimulationResult.MatchedPolicy(
                                RoutingSimulationOutcome.AUTO_REJECT, null, "Draft", true, null))),
                List.of()));

        assertThat(response.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.queryRequestId()).isEqualTo(queryId);
            assertThat(sample.baseline()).isNull();
            assertThat(sample.simulated().isDraft()).isTrue();
            assertThat(sample.simulated().policyId()).isNull();
            assertThat(sample.simulated().policyName()).isEqualTo("Draft");
            assertThat(sample.historicalStatus()).isEqualTo(QueryStatus.EXECUTED);
        });
    }

    @Test
    void mapsAPersistedBaselineMatchWithItsPolicyId() {
        var policyId = UUID.randomUUID();
        var response = RoutingSimulationResponse.from(new RoutingSimulationResult(
                FROM, TO, null, 1, 1, false, List.of(), List.of(),
                List.of(new RoutingSimulationResult.Sample(UUID.randomUUID(), "a@x.io", "prod",
                        QueryType.SELECT, QueryStatus.EXECUTED, FROM,
                        new RoutingSimulationResult.MatchedPolicy(
                                RoutingSimulationOutcome.ESCALATE, policyId, "Existing", false, 2),
                        null)),
                List.of()));

        var baseline = response.samples().get(0).baseline();
        assertThat(baseline.policyId()).isEqualTo(policyId);
        assertThat(baseline.isDraft()).isFalse();
        assertThat(baseline.requiredApprovals()).isEqualTo(2);
        assertThat(response.samples().get(0).simulated()).isNull();
    }
}
