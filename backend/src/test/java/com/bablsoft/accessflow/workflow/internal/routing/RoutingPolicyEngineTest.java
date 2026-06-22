package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingPolicyEngineTest {

    @Mock RoutingPolicyRepository routingPolicyRepository;

    private final RoutingConditionCodec codec = new RoutingConditionCodec(
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
            messageSource());
    private RoutingPolicyEngine engine;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @BeforeEach
    void setUp() {
        engine = new RoutingPolicyEngine(routingPolicyRepository, codec,
                new RoutingConditionEvaluator());
    }

    private ConditionContext deleteContext() {
        return new ConditionContext(QueryType.DELETE, Set.of("payroll.salaries"), RiskLevel.HIGH, 80,
                UserRoleType.ANALYST, Set.of(), LocalDateTime.of(2026, 6, 3, 10, 0),
                false, false, false, "203.0.113.7", "curl/8.4.0", false, 120, false);
    }

    @Test
    void returnsFirstMatchByPriority() {
        var nonMatch = policy(1, RoutingAction.AUTO_APPROVE,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)));
        var match = policy(2, RoutingAction.AUTO_REJECT,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)));
        when(routingPolicyRepository.findEnabledForEvaluation(orgId, datasourceId))
                .thenReturn(List.of(nonMatch, match));

        var result = engine.evaluate(orgId, datasourceId, deleteContext());

        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo(RoutingAction.AUTO_REJECT);
        assertThat(result.get().policyId()).isEqualTo(match.getId());
    }

    @Test
    void earlierMatchingPolicyWinsOverLaterOne() {
        var first = policy(1, RoutingAction.ESCALATE,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)));
        var second = policy(2, RoutingAction.AUTO_REJECT,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)));
        when(routingPolicyRepository.findEnabledForEvaluation(orgId, datasourceId))
                .thenReturn(List.of(first, second));

        var result = engine.evaluate(orgId, datasourceId, deleteContext());

        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo(RoutingAction.ESCALATE);
    }

    @Test
    void emptyWhenNoPolicyMatches() {
        var nonMatch = policy(1, RoutingAction.AUTO_APPROVE,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)));
        when(routingPolicyRepository.findEnabledForEvaluation(orgId, datasourceId))
                .thenReturn(List.of(nonMatch));

        assertThat(engine.evaluate(orgId, datasourceId, deleteContext())).isEmpty();
    }

    @Test
    void emptyWhenNoPolicies() {
        when(routingPolicyRepository.findEnabledForEvaluation(orgId, datasourceId))
                .thenReturn(List.of());

        assertThat(engine.evaluate(orgId, datasourceId, deleteContext())).isEmpty();
    }

    @Test
    void skipsPolicyWithUndecodableConditionAndUsesNext() {
        var broken = policy(1, RoutingAction.AUTO_APPROVE, null);
        broken.setConditionJson("{\"type\":\"garbage\"}");
        var good = policy(2, RoutingAction.AUTO_REJECT,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)));
        when(routingPolicyRepository.findEnabledForEvaluation(orgId, datasourceId))
                .thenReturn(List.of(broken, good));

        var result = engine.evaluate(orgId, datasourceId, deleteContext());

        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo(RoutingAction.AUTO_REJECT);
    }

    private RoutingPolicyEntity policy(int priority, RoutingAction action, ConditionNode condition) {
        var entity = new RoutingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setName("policy-" + priority);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setAction(action);
        if (condition != null) {
            entity.setConditionJson(codec.encode(condition));
        }
        return entity;
    }
}
