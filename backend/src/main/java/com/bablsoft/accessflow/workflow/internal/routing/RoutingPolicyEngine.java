package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Evaluates a query's {@link ConditionContext} against the organisation's enabled routing policies
 * and returns the first match by ascending priority. Org-wide policies ({@code datasource_id IS
 * NULL}) and policies bound to the query's datasource are considered together, ordered by priority.
 * A policy whose stored condition fails to decode is logged and skipped so one bad row cannot break
 * routing for the whole org.
 */
@Component
@RequiredArgsConstructor
public class RoutingPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingPolicyEngine.class);

    private final RoutingPolicyRepository routingPolicyRepository;
    private final RoutingConditionCodec routingConditionCodec;
    private final RoutingConditionEvaluator routingConditionEvaluator;

    public Optional<RoutingMatch> evaluate(UUID organizationId, UUID datasourceId,
                                           ConditionContext context) {
        var policies = routingPolicyRepository.findEnabledForEvaluation(organizationId, datasourceId);
        for (RoutingPolicyEntity policy : policies) {
            if (matches(policy, context)) {
                return Optional.of(new RoutingMatch(policy.getId(), policy.getName(),
                        policy.getAction(), policy.getRequiredApprovals(), policy.getReason()));
            }
        }
        return Optional.empty();
    }

    private boolean matches(RoutingPolicyEntity policy, ConditionContext context) {
        try {
            var condition = routingConditionCodec.decode(policy.getConditionJson());
            return routingConditionEvaluator.matches(condition, context);
        } catch (RuntimeException ex) {
            log.error("Skipping routing policy {} with undecodable condition", policy.getId(), ex);
            return false;
        }
    }
}
