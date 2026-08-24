package com.bablsoft.accessflow.deploygov.internal.routing;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRoutingPolicyEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Evaluates deployment routing policies (lowest {@code priority} first) against a submitted
 * deployment. A policy whose {@code pipeline_id} is set only applies to that pipeline; conditions
 * are AND-ed across the leaves that are present, and the first matching enabled policy wins.
 *
 * <p><strong>A policy that cannot be evaluated is skipped</strong> — unparseable conditions, a
 * timezone no longer in the tz database — logged at WARN. This is deliberately the opposite of
 * {@code FreezeWindowEvaluator}, which fails <em>closed</em> to an active HOLD: a broken freeze must
 * still hold deployments, but a broken routing policy must never silently auto-approve or
 * auto-reject one. Skipping drops the deployment through to the environment's {@code require_review}
 * (which defaults to true), which is the safe direction here.
 */
@Component
@RequiredArgsConstructor
public class DeploymentRoutingPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRoutingPolicyEngine.class);

    private final DeploymentRoutingPolicyRepository repository;
    private final DeploymentRoutingConditionCodec codec;

    /** The first matching policy, or {@code null} when none matches. */
    public RoutingMatch evaluate(UUID organizationId, UUID pipelineId, RoutingContext context) {
        for (var policy : repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(organizationId)) {
            if (policy.getPipelineId() != null && !policy.getPipelineId().equals(pipelineId)) {
                continue;
            }
            if (matches(policy, context)) {
                return new RoutingMatch(policy.getId(), policy.getAction(),
                        policy.getRequiredApprovals());
            }
        }
        return null;
    }

    private boolean matches(DeploymentRoutingPolicyEntity policy, RoutingContext context) {
        DeploymentRoutingConditions conditions;
        try {
            conditions = codec.fromJson(policy.getConditions());
        } catch (DeploymentRoutingConditionCodec.ConditionsParseException ex) {
            log.warn("Skipping deployment routing policy {} with unreadable conditions: {}",
                    policy.getId(), ex.getMessage());
            return false;
        }
        try {
            return matchesEnvironment(conditions, context)
                    && matchesProvider(conditions, context)
                    && matchesRisk(conditions, context)
                    && matchesVersion(conditions, context)
                    && matchesTimeWindow(conditions, context);
        } catch (DateTimeException ex) {
            log.warn("Skipping deployment routing policy {} that could not be evaluated: {}",
                    policy.getId(), ex.getMessage());
            return false;
        }
    }

    private static boolean matchesEnvironment(DeploymentRoutingConditions conditions,
                                              RoutingContext context) {
        if (conditions.environments().isEmpty()) {
            return true;
        }
        return context.environmentName() != null
                && conditions.environments().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(context.environmentName()));
    }

    // Compared as strings, never through PipelineProvider.valueOf: an admin typo must not throw at
    // evaluation time, it must simply not match.
    private static boolean matchesProvider(DeploymentRoutingConditions conditions,
                                           RoutingContext context) {
        if (conditions.providers().isEmpty()) {
            return true;
        }
        return context.provider() != null
                && conditions.providers().stream()
                        .anyMatch(p -> p.equalsIgnoreCase(context.provider().name()));
    }

    // An absent risk signal (AI disabled, or the analysis was skipped) never satisfies a risk gate,
    // so such a deployment falls through to the environment's own review policy.
    private static boolean matchesRisk(DeploymentRoutingConditions conditions, RoutingContext context) {
        if (conditions.minRiskLevel() == null) {
            return true;
        }
        RiskLevel actual = context.riskLevel();
        return actual != null && actual.ordinal() >= conditions.minRiskLevel().ordinal();
    }

    private static boolean matchesVersion(DeploymentRoutingConditions conditions,
                                          RoutingContext context) {
        if (conditions.versionGlobs().isEmpty()) {
            return true;
        }
        return conditions.versionGlobs().stream()
                .anyMatch(glob -> GlobMatcher.matches(glob, context.version()));
    }

    /**
     * Day-of-week and time-of-day evaluated as wall-clock in the policy's own IANA timezone
     * (default UTC). Window is {@code [startTime, endTime)}; an {@code endTime} before
     * {@code startTime} spans midnight, with day membership belonging to the day the window starts —
     * the same rule {@code FreezeWindowEvaluator} applies to recurring freeze windows. Days without
     * times mean the whole local day; times without days mean every day; <em>one</em> time without
     * the other is rejected rather than treated as unconstrained.
     */
    private static boolean matchesTimeWindow(DeploymentRoutingConditions conditions,
                                             RoutingContext context) {
        var days = conditions.daysOfWeek();
        var start = conditions.startTime();
        var end = conditions.endTime();
        if (days.isEmpty() && start == null && end == null) {
            return true;
        }
        // A half-specified window is not a window. Treating it as "unconstrained" would make the
        // whole condition set match every deployment — an AUTO_APPROVE policy an admin wrote for a
        // maintenance hour would then approve everything, around the clock. Skip the policy instead;
        // the admin boundary rejects this shape, so only a hand-edited row can reach here.
        if ((start == null) != (end == null)) {
            throw new DateTimeException(
                    "routing time window needs both a start and an end time, or neither");
        }
        var zone = ZoneId.of(conditions.timezone() == null || conditions.timezone().isBlank()
                ? "UTC" : conditions.timezone());
        var zoned = context.at().atZone(zone);
        var day = zoned.getDayOfWeek().getValue();
        var previousDay = zoned.getDayOfWeek().minus(1).getValue();
        var time = zoned.toLocalTime();
        if (start == null) {
            // Days alone: the whole local day, in the policy's zone.
            return days.contains(day);
        }
        if (start.equals(end)) {
            throw new DateTimeException("routing time window start and end are equal");
        }
        boolean dayListed = days.isEmpty() || days.contains(day);
        boolean previousDayListed = days.isEmpty() || days.contains(previousDay);
        if (start.isBefore(end)) {
            return dayListed && inRange(time, start, end);
        }
        return (dayListed && !time.isBefore(start)) || (previousDayListed && time.isBefore(end));
    }

    private static boolean inRange(LocalTime time, LocalTime start, LocalTime end) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    /** What the engine matches against. {@code at} is the evaluation instant. */
    public record RoutingContext(String environmentName, PipelineProvider provider, String version,
                                 RiskLevel riskLevel, Instant at) {
    }

    /** The winning policy. {@code requiredApprovals} is null for the AUTO_* actions. */
    public record RoutingMatch(UUID policyId, DeploymentRoutingAction action,
                               Integer requiredApprovals) {
    }
}
