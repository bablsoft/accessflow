package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
        return firstMatch(enabledFor(organizationId, datasourceId), context)
                .map(EvaluablePolicy::toMatch);
    }

    /**
     * The org's enabled policies for this datasource, decoded and in evaluation order. Exposed so
     * the policy simulator (issue AF-630) can run the identical first-match rule over a set that
     * includes an unsaved draft — a second ordering implementation would be free to disagree with
     * the one that routes real queries.
     */
    public List<EvaluablePolicy> enabledFor(UUID organizationId, UUID datasourceId) {
        var policies = routingPolicyRepository.findEnabledForEvaluation(organizationId, datasourceId);
        var evaluable = new ArrayList<EvaluablePolicy>(policies.size());
        for (RoutingPolicyEntity policy : policies) {
            var condition = decode(policy);
            if (condition != null) {
                evaluable.add(new EvaluablePolicy(policy.getId(), policy.getName(),
                        policy.getPriority(), policy.getAction(), policy.getRequiredApprovals(),
                        policy.getReason(), condition, false));
            }
        }
        return evaluable;
    }

    /**
     * First match by the supplied order wins; evaluation stops there. A policy that throws while
     * being evaluated is logged and skipped, exactly as an undecodable one is — one bad stored row
     * must never break routing for the whole organization.
     */
    public Optional<EvaluablePolicy> firstMatch(List<EvaluablePolicy> policies,
                                               ConditionContext context) {
        for (var policy : policies) {
            if (matches(policy, context)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /**
     * Every policy with its match flag, in the same order and by the same rule as
     * {@link #firstMatch} (issue AF-859). The access explainer shows the whole ordered list, because
     * a policy that <em>nearly</em> matched is usually the most useful line in a trace — and it goes
     * through the shared {@link #matches} helper so that view can never disagree with the one that
     * routes.
     *
     * <p>Deliberately not used on the live path, which short-circuits at the first match.
     */
    public List<PolicyEvaluation> evaluateAll(List<EvaluablePolicy> policies,
                                              ConditionContext context) {
        var evaluations = new ArrayList<PolicyEvaluation>(policies.size());
        boolean decided = false;
        for (var policy : policies) {
            boolean matched = matches(policy, context);
            // "decided" marks the winner: later policies are still reported as matching so an admin
            // can see the overlap, but only the first one actually routes.
            evaluations.add(new PolicyEvaluation(policy, matched, matched && !decided));
            decided |= matched;
        }
        return List.copyOf(evaluations);
    }

    /** One policy's verdict for the explainer. {@code decisive} marks the first match. */
    public record PolicyEvaluation(EvaluablePolicy policy, boolean matched, boolean decisive) {
    }

    private boolean matches(EvaluablePolicy policy, ConditionContext context) {
        try {
            return routingConditionEvaluator.matches(policy.condition(), context);
        } catch (RuntimeException ex) {
            log.error("Skipping routing policy {} that failed to evaluate", policy.id(), ex);
            return false;
        }
    }

    /** A policy whose stored condition will not decode is logged and skipped, never fatal. */
    private ConditionNode decode(RoutingPolicyEntity policy) {
        try {
            return routingConditionCodec.decode(policy.getConditionJson());
        } catch (RuntimeException ex) {
            log.error("Skipping routing policy {} with undecodable condition", policy.getId(), ex);
            return null;
        }
    }
}
