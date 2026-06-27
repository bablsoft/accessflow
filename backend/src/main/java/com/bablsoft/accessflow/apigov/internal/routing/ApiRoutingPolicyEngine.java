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
        for (var policy : repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(organizationId)) {
            if (policy.getConnectorId() != null && !policy.getConnectorId().equals(connectorId)) {
                continue;
            }
            if (matches(policy, context)) {
                return new RoutingMatch(policy.getId(), policy.getAction(), policy.getRequiredApprovals());
            }
        }
        return null;
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

    public record RoutingMatch(UUID policyId, ApiRoutingAction action, Integer requiredApprovals) {
    }
}
