package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.proxy.api.MaskingSimulationResult;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult.Transition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Wire mapping for the row-security and masking dry runs (issue AF-630). */
class PolicySimulationWebModelsTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-01T00:00:00Z");

    // ---- row security ----------------------------------------------------------------------------

    @Test
    void rowSecurityDraftMapsOntoTheCommand() {
        var replaces = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var command = new SimulateRowSecurityPolicyRequest.Draft(replaces, "public.orders",
                "tenant_id", RowSecurityOperator.EQUALS, RowSecurityValueType.VARIABLE,
                "user.tenant", List.of("ANALYST"), List.of(groupId), List.of(), Boolean.TRUE)
                .toCommand();

        assertThat(command.replacesPolicyId()).isEqualTo(replaces);
        assertThat(command.tableName()).isEqualTo("public.orders");
        assertThat(command.valueType()).isEqualTo(RowSecurityValueType.VARIABLE);
        assertThat(command.appliesToRoles()).containsExactly("ANALYST");
        assertThat(command.appliesToGroupIds()).containsExactly(groupId);
        assertThat(command.enabled()).isTrue();
    }

    @Test
    void rowSecurityDraftDefaultsAnOmittedEnabledFlagToTrue() {
        var command = new SimulateRowSecurityPolicyRequest.Draft(null, "orders", "tenant",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "acme", null, null, null,
                null).toCommand();

        assertThat(command.enabled()).isTrue();
        assertThat(command.appliesToRoles()).isEmpty();
        assertThat(command.appliesToGroupIds()).isEmpty();
        assertThat(command.appliesToUserIds()).isEmpty();
    }

    @Test
    void rowSecurityDraftHonoursAnExplicitFalseEnabledFlag() {
        assertThat(new SimulateRowSecurityPolicyRequest.Draft(null, "orders", "tenant",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "acme", null, null, null,
                Boolean.FALSE).toCommand().enabled()).isFalse();
    }

    @Test
    void rowSecurityResponseMapsAnEmptyResultWithoutNulls() {
        var response = RowSecuritySimulationResponse.from(new RowSecuritySimulationResult(
                FROM, TO, null, 0, 0, 0, false, null, null, null, null));

        assertThat(response.transitionCounts()).isEmpty();
        assertThat(response.userImpacts()).isEmpty();
        assertThat(response.samples()).isEmpty();
        assertThat(response.caveats()).isEmpty();
    }

    @Test
    void rowSecurityResponseRendersTransitionsAsStableStrings() {
        var datasourceId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var response = RowSecuritySimulationResponse.from(new RowSecuritySimulationResult(
                FROM, TO, datasourceId, 812, 41, 3, true,
                List.of(new RowSecuritySimulationResult.TransitionCount(
                        Transition.NEWLY_FAILS_CLOSED, 9)),
                List.of(new RowSecuritySimulationResult.UserImpact(userId, "a@x.io", "Ada", 20, 12,
                        9)),
                List.of(new RowSecuritySimulationResult.Sample(queryId, "a@x.io", QueryType.SELECT,
                        FROM, Transition.NEWLY_FAILS_CLOSED, RowSecurityOutcome.NOT_APPLICABLE,
                        RowSecurityOutcome.FAIL_CLOSED, "UNION over a protected table")),
                List.of(SimulationCaveat.ENGINE_CLASSIFICATION_UNAVAILABLE)));

        assertThat(response.unclassifiableCount()).isEqualTo(3);
        assertThat(response.truncated()).isTrue();
        assertThat(response.transitionCounts()).singleElement().satisfies(t -> {
            assertThat(t.transition()).isEqualTo("NEWLY_FAILS_CLOSED");
            assertThat(t.count()).isEqualTo(9);
        });
        assertThat(response.userImpacts().get(0).newlyFailsClosedCount()).isEqualTo(9);
        assertThat(response.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.queryRequestId()).isEqualTo(queryId);
            assertThat(sample.transition()).isEqualTo("NEWLY_FAILS_CLOSED");
            assertThat(sample.simulatedOutcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
            assertThat(sample.reason()).isEqualTo("UNION over a protected table");
        });
        assertThat(response.caveats())
                .containsExactly(SimulationCaveat.ENGINE_CLASSIFICATION_UNAVAILABLE);
    }

    // ---- masking ----------------------------------------------------------------------------------

    @Test
    void maskingDraftMapsOntoTheCommand() {
        var replaces = UUID.randomUUID();
        var command = new SimulateMaskingPolicyRequest.Draft(replaces, "customers.email",
                MaskingStrategy.PARTIAL, Map.of("visible_suffix", "4"), List.of("ADMIN"), null, null,
                Boolean.TRUE).toCommand();

        assertThat(command.replacesPolicyId()).isEqualTo(replaces);
        assertThat(command.columnRef()).isEqualTo("customers.email");
        assertThat(command.strategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(command.strategyParams()).containsEntry("visible_suffix", "4");
        assertThat(command.revealToRoles()).containsExactly("ADMIN");
        assertThat(command.revealToGroupIds()).isEmpty();
        assertThat(command.enabled()).isTrue();
    }

    @Test
    void maskingDraftDefaultsAnOmittedEnabledFlagToTrue() {
        var command = new SimulateMaskingPolicyRequest.Draft(null, "email", MaskingStrategy.FULL,
                null, null, null, null, null).toCommand();

        assertThat(command.enabled()).isTrue();
        assertThat(command.strategyParams()).isEmpty();
    }

    @Test
    void maskingResponseMapsAnEmptyResultWithoutNulls() {
        var response = MaskingSimulationResponse.from(new MaskingSimulationResult(
                FROM, TO, null, 0, 0, 0, 0, false, null, null, null, null));

        assertThat(response.userImpacts()).isEmpty();
        assertThat(response.columnImpacts()).isEmpty();
        assertThat(response.samples()).isEmpty();
        assertThat(response.caveats()).isEmpty();
    }

    @Test
    void maskingResponseMapsCountsImpactsAndSamples() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var response = MaskingSimulationResponse.from(new MaskingSimulationResult(
                FROM, TO, datasourceId, 812, 130, 130, 0, false,
                List.of(new MaskingSimulationResult.UserImpact(userId, "a@x.io", "Ada",
                        List.of("email"), List.of(), 43)),
                List.of(new MaskingSimulationResult.ColumnImpact("email", 130, 0)),
                List.of(new MaskingSimulationResult.Sample(queryId, "a@x.io", FROM,
                        List.of("email"), List.of())),
                List.of(SimulationCaveat.COLUMN_MATCH_BARE_NAME)));

        assertThat(response.evaluatedCount()).isEqualTo(812);
        assertThat(response.newlyMaskedCount()).isEqualTo(130);
        assertThat(response.newlyRevealedCount()).isZero();
        assertThat(response.datasourceId()).isEqualTo(datasourceId);
        assertThat(response.userImpacts().get(0).newlyMaskedColumns()).containsExactly("email");
        assertThat(response.userImpacts().get(0).affectedQueryCount()).isEqualTo(43);
        assertThat(response.columnImpacts().get(0).columnName()).isEqualTo("email");
        assertThat(response.samples().get(0).queryRequestId()).isEqualTo(queryId);
        assertThat(response.caveats()).containsExactly(SimulationCaveat.COLUMN_MATCH_BARE_NAME);
    }
}
