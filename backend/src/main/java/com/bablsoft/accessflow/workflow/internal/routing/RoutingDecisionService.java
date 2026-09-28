package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingDecisionSource;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingDecisionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingDecisionRepository;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Records the {@code routing_decision} for a matched query and drives the corresponding state
 * transition atomically (one transaction), and exposes the persisted override for the review
 * service and the matched-policy view for the query-detail read path.
 */
@Service
@RequiredArgsConstructor
public class RoutingDecisionService {

    private final RoutingDecisionRepository routingDecisionRepository;
    private final RoutingPolicyRepository routingPolicyRepository;
    private final QueryRequestStateService queryRequestStateService;

    /**
     * Persist the routing decision and transition the query out of {@code PENDING_AI} in the same
     * transaction. {@code effectiveMinApprovals} is the resolved absolute approval count for
     * ESCALATE / REQUIRE_APPROVALS, or {@code null} for the auto-* actions.
     */
    @Transactional
    public void applyDecision(UUID queryRequestId, QueryStatus nextStatus, RoutingMatch match,
                              Integer effectiveMinApprovals) {
        var entity = new RoutingDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(queryRequestId);
        entity.setMatchedPolicyId(match.policyId());
        entity.setAction(match.action());
        entity.setEffectiveMinApprovals(effectiveMinApprovals);
        entity.setReason(match.reason());
        routingDecisionRepository.save(entity);
        queryRequestStateService.transitionTo(queryRequestId, QueryStatus.PENDING_AI, nextStatus);
    }

    /**
     * The external decision hook's counterpart of {@link #applyDecision} (#945): the row carries
     * {@code source = DECISION_HOOK}, the hook id and no policy, and the action is the routing
     * action the hook's answer maps onto — {@code AUTO_REJECT}, {@code ESCALATE} or
     * {@code REQUIRE_APPROVALS}; never an approval.
     */
    @Transactional
    public void applyHookDecision(UUID queryRequestId, QueryStatus nextStatus, RoutingAction action,
                                  Integer effectiveMinApprovals, String reason,
                                  UUID decisionHookId) {
        if (action == RoutingAction.AUTO_APPROVE) {
            throw new IllegalArgumentException("A decision hook can never approve");
        }
        var entity = new RoutingDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(queryRequestId);
        entity.setAction(action);
        entity.setEffectiveMinApprovals(effectiveMinApprovals);
        entity.setReason(reason);
        entity.setSource(RoutingDecisionSource.DECISION_HOOK);
        entity.setDecisionHookId(decisionHookId);
        routingDecisionRepository.save(entity);
        queryRequestStateService.transitionTo(queryRequestId, QueryStatus.PENDING_AI, nextStatus);
    }

    @Transactional(readOnly = true)
    public Optional<Integer> findEffectiveMinApprovals(UUID queryRequestId) {
        return routingDecisionRepository.findByQueryRequestId(queryRequestId)
                .map(RoutingDecisionEntity::getEffectiveMinApprovals)
                .filter(Objects::nonNull);
    }

    @Transactional(readOnly = true)
    public Optional<MatchedRoutingPolicyView> findMatchedPolicy(UUID queryRequestId) {
        return routingDecisionRepository.findByQueryRequestId(queryRequestId)
                .map(decision -> new MatchedRoutingPolicyView(
                        decision.getMatchedPolicyId(),
                        resolvePolicyName(decision.getMatchedPolicyId()),
                        decision.getAction(),
                        decision.getReason(),
                        decision.getSource(),
                        decision.getDecisionHookId()));
    }

    private String resolvePolicyName(UUID policyId) {
        if (policyId == null) {
            return null;
        }
        return routingPolicyRepository.findById(policyId)
                .map(p -> p.getName())
                .orElse(null);
    }
}
