package com.bablsoft.accessflow.apigov.internal.routing;

import com.bablsoft.accessflow.apigov.api.ApiRoutingAction;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRoutingPolicyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRoutingPolicyRepository;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiRoutingPolicyEngineTest {

    @Mock private ApiRoutingPolicyRepository repository;
    private ApiRoutingPolicyEngine engine;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = new ApiRoutingPolicyEngine(repository, JsonMapper.builder().build());
    }

    private ApiRoutingPolicyEntity policy(String conditions, ApiRoutingAction action, UUID connector) {
        var p = new ApiRoutingPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setOrganizationId(orgId);
        p.setConnectorId(connector);
        p.setConditions(conditions);
        p.setAction(action);
        return p;
    }

    @Test
    void matchesWriteCondition() {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(policy("{\"write\":true}", ApiRoutingAction.REQUIRE_APPROVALS, null)));

        var match = engine.evaluate(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("POST", true, "createPet", RiskLevel.LOW));

        assertThat(match).isNotNull();
        assertThat(match.action()).isEqualTo(ApiRoutingAction.REQUIRE_APPROVALS);
    }

    @Test
    void writeConditionDoesNotMatchRead() {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(policy("{\"write\":true}", ApiRoutingAction.AUTO_REJECT, null)));

        var match = engine.evaluate(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, RiskLevel.LOW));

        assertThat(match).isNull();
    }

    @Test
    void minRiskLevelGate() {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(policy("{\"minRiskLevel\":\"HIGH\"}", ApiRoutingAction.ESCALATE, null)));

        assertThat(engine.evaluate(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, RiskLevel.CRITICAL))).isNotNull();
        assertThat(engine.evaluate(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, RiskLevel.LOW))).isNull();
    }

    @Test
    void connectorScopedPolicyIgnoredForOtherConnector() {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(policy("{}", ApiRoutingAction.AUTO_APPROVE, UUID.randomUUID())));

        assertThat(engine.evaluate(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, null))).isNull();
    }

    @Test
    void noPoliciesReturnsNull() {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId)).thenReturn(List.of());
        assertThat(engine.evaluate(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, null))).isNull();
    }
    // ── evaluateAll: the presentational read behind the decision trace (AF-967) ──

    @Test
    void evaluateAllReportsEveryPolicyInPriorityOrderWithOnlyTheFirstMatchDecisive() {
        var first = policy("{\"write\":true}", ApiRoutingAction.AUTO_REJECT, null);
        first.setName("block writes");
        first.setPriority(5);
        var second = policy("{}", ApiRoutingAction.ESCALATE, null);
        second.setName("escalate everything");
        second.setPriority(10);
        second.setRequiredApprovals(2);
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(first, second));

        var evaluations = engine.evaluateAll(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("POST", true, "createPet", RiskLevel.LOW));

        assertThat(evaluations).hasSize(2);
        assertThat(evaluations.get(0).name()).isEqualTo("block writes");
        assertThat(evaluations.get(0).priority()).isEqualTo(5);
        assertThat(evaluations.get(0).matched()).isTrue();
        assertThat(evaluations.get(0).decisive()).isTrue();
        // The second policy also matches — an admin needs to see the overlap — but only the first
        // one routes.
        assertThat(evaluations.get(1).matched()).isTrue();
        assertThat(evaluations.get(1).decisive()).isFalse();
        assertThat(evaluations.get(1).requiredApprovals()).isEqualTo(2);
    }

    @Test
    void evaluateAllReportsUnmatchedPoliciesRatherThanOmittingThem() {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(policy("{\"write\":true}", ApiRoutingAction.AUTO_REJECT, null)));

        var evaluations = engine.evaluateAll(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, RiskLevel.LOW));

        assertThat(evaluations).singleElement().satisfies(e -> {
            assertThat(e.matched()).isFalse();
            assertThat(e.decisive()).isFalse();
        });
    }

    @Test
    void evaluateAllAppliesTheSameConnectorScopingAsEvaluate() {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(policy("{}", ApiRoutingAction.AUTO_APPROVE, UUID.randomUUID())));

        assertThat(engine.evaluateAll(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, RiskLevel.LOW)))
                .isEmpty();
    }

    @Test
    void theMatchCarriesThePolicyNameForTheTrace() {
        var entity = policy("{}", ApiRoutingAction.AUTO_APPROVE, null);
        entity.setName("auto-approve everything");
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(entity));

        var match = engine.evaluate(orgId, connectorId,
                new ApiRoutingPolicyEngine.RoutingContext("GET", false, null, RiskLevel.LOW));

        assertThat(match.policyName()).isEqualTo("auto-approve everything");
    }

}
