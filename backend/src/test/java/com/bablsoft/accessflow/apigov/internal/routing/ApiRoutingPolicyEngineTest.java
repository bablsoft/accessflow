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
}
