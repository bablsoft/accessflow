package com.bablsoft.accessflow.apigov.internal.routing;

import com.bablsoft.accessflow.apigov.api.ApiRoutingAction;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRoutingPolicyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRoutingPolicyRepository;
import com.bablsoft.accessflow.core.api.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates API routing policies (lowest {@code priority} first) against a submitted call. A policy's
 * {@code conditions} JSON may constrain {@code write} (boolean), {@code verbs} (array),
 * {@code operations} (array of operation ids), and {@code minRiskLevel} (LOW/MEDIUM/HIGH/CRITICAL);
 * an absent key is unconstrained. The first matching enabled policy wins.
 */
@Component
@RequiredArgsConstructor
public class ApiRoutingPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(ApiRoutingPolicyEngine.class);

    private final ApiRoutingPolicyRepository repository;
    private final ObjectMapper objectMapper;

    public RoutingMatch evaluate(UUID organizationId, UUID connectorId, RoutingContext context) {
        for (var policy : scopedTo(organizationId, connectorId)) {
            if (matches(policy, context)) {
                return new RoutingMatch(policy.getId(), policy.getName(), policy.getAction(),
                        policy.getRequiredApprovals());
            }
        }
        return null;
    }

    /**
     * Every policy in scope with its match flag, in the same order and through the same
     * {@link #matches} helper as {@link #evaluate} (issue AF-967). The decision trace shows the whole
     * ordered list, because a policy that <em>nearly</em> matched is usually the most useful line in
     * it, and sharing the helper is what stops that view from disagreeing with the one that routes.
     *
     * <p>Deliberately not used on the live path, which short-circuits at the first match.
     */
    public List<PolicyEvaluation> evaluateAll(UUID organizationId, UUID connectorId,
                                              RoutingContext context) {
        var evaluations = new ArrayList<PolicyEvaluation>();
        boolean decided = false;
        for (var policy : scopedTo(organizationId, connectorId)) {
            boolean matched = matches(policy, context);
            // "decided" marks the winner: later policies are still reported as matching so an admin
            // can see the overlap, but only the first one actually routes.
            evaluations.add(new PolicyEvaluation(policy.getId(), policy.getName(),
                    policy.getPriority(), policy.getAction(), policy.getRequiredApprovals(),
                    matched, matched && !decided));
            decided |= matched;
        }
        return List.copyOf(evaluations);
    }

    /** The org's enabled policies that apply to this connector, in ascending priority order. */
    private List<ApiRoutingPolicyEntity> scopedTo(UUID organizationId, UUID connectorId) {
        return repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(organizationId).stream()
                .filter(policy -> policy.getConnectorId() == null
                        || policy.getConnectorId().equals(connectorId))
                .toList();
    }

    private boolean matches(ApiRoutingPolicyEntity policy, RoutingContext context) {
        JsonNode conditions;
        try {
            conditions = objectMapper.readTree(policy.getConditions() == null ? "{}" : policy.getConditions());
        } catch (RuntimeException ex) {
            log.warn("Skipping API routing policy {} with unparseable conditions", policy.getId());
            return false;
        }
        if (conditions.has("write") && conditions.get("write").asBoolean() != context.write()) {
            return false;
        }
        if (conditions.has("verbs") && !arrayContains(conditions.get("verbs"), context.verb())) {
            return false;
        }
        if (conditions.has("operations") && !arrayContains(conditions.get("operations"), context.operationId())) {
            return false;
        }
        if (conditions.has("minRiskLevel") && !meetsRisk(conditions.get("minRiskLevel").asString(), context.riskLevel())) {
            return false;
        }
        return true;
    }

    private static boolean arrayContains(JsonNode array, String value) {
        if (value == null || !array.isArray()) {
            return false;
        }
        for (var node : array) {
            if (value.equalsIgnoreCase(node.asString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean meetsRisk(String min, RiskLevel actual) {
        if (actual == null || min == null) {
            return false;
        }
        try {
            return actual.ordinal() >= RiskLevel.valueOf(min.toUpperCase()).ordinal();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public record RoutingContext(String verb, boolean write, String operationId, RiskLevel riskLevel) {
    }

    public record RoutingMatch(UUID policyId, String policyName, ApiRoutingAction action,
                               Integer requiredApprovals) {
    }

    /** One policy's verdict for the decision trace. {@code decisive} marks the first match. */
    public record PolicyEvaluation(UUID policyId, String name, int priority, ApiRoutingAction action,
                                   Integer requiredApprovals, boolean matched, boolean decisive) {
    }
}
