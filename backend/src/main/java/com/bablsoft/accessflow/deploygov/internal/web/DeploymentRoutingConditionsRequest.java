package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Wire shape of a routing policy's conditions. Every field is optional; an omitted or empty one is
 * unconstrained, so an empty object matches every deployment.
 */
public record DeploymentRoutingConditionsRequest(
        List<String> environments,
        List<String> providers,
        RiskLevel minRiskLevel,
        List<String> versionGlobs,
        Set<Integer> daysOfWeek,
        LocalTime startTime,
        LocalTime endTime,

        @Size(max = 64, message = "{validation.deployment_routing_policy.timezone.size}")
        String timezone) {

    DeploymentRoutingConditions toConditions() {
        return new DeploymentRoutingConditions(environments, providers, minRiskLevel, versionGlobs,
                daysOfWeek, startTime, endTime, timezone);
    }

    static DeploymentRoutingConditionsRequest from(DeploymentRoutingConditions conditions) {
        if (conditions == null) {
            return null;
        }
        return new DeploymentRoutingConditionsRequest(conditions.environments(),
                conditions.providers(), conditions.minRiskLevel(), conditions.versionGlobs(),
                conditions.daysOfWeek(), conditions.startTime(), conditions.endTime(),
                conditions.timezone());
    }
}
