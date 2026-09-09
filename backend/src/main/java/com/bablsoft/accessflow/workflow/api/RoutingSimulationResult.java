package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SimulationCaveat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The diff between two evaluations of the same historical corpus — once against the organization's
 * current routing policies, once against those policies with a draft applied (issue AF-630).
 *
 * <p>It is deliberately an A/B rather than a comparison with what actually happened: real outcomes
 * are confounded by the grant fast path, review-plan fall-through, break-glass, external ticket
 * decisions and the AI-failure path, none of which the draft changes. Both arms see identical
 * inputs, so every confound cancels and what remains is the effect of the edit.
 *
 * @param evaluatedCount rows actually replayed (never more than the configured cap)
 * @param changedCount   rows whose outcome differs between the two arms
 * @param truncated      more rows matched the window than the cap allowed; counts cover the
 *                       evaluated subset only
 */
public record RoutingSimulationResult(
        Instant periodFrom,
        Instant periodTo,
        UUID datasourceId,
        int evaluatedCount,
        int changedCount,
        boolean truncated,
        List<OutcomeDelta> outcomeDeltas,
        List<UserImpact> userImpacts,
        List<Sample> samples,
        List<SimulationCaveat> caveats) {

    public RoutingSimulationResult {
        outcomeDeltas = outcomeDeltas == null ? List.of() : List.copyOf(outcomeDeltas);
        userImpacts = userImpacts == null ? List.of() : List.copyOf(userImpacts);
        samples = samples == null ? List.of() : List.copyOf(samples);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }

    /** How many rows moved from one outcome to another. Only differing pairs are listed. */
    public record OutcomeDelta(RoutingSimulationOutcome baseline, RoutingSimulationOutcome simulated,
                               int count) {
    }

    /** Per-submitter blast radius, ordered by {@code changedCount} descending. */
    public record UserImpact(UUID userId, String email, String displayName, int changedCount,
                             List<RoutingSimulationOutcome> simulatedOutcomes) {
        public UserImpact {
            simulatedOutcomes = simulatedOutcomes == null ? List.of() : List.copyOf(simulatedOutcomes);
        }
    }

    /**
     * One changed request, for drill-down. Carries no SQL text: {@code ROUTING_POLICY_MANAGE} does
     * not otherwise grant read access to other people's queries, and a simulation must not become a
     * side channel for them. The query id is enough for a caller who does hold that access to
     * follow the link.
     */
    public record Sample(UUID queryRequestId, String submittedByEmail, String datasourceName,
                         QueryType queryType, QueryStatus historicalStatus, Instant createdAt,
                         MatchedPolicy baseline, MatchedPolicy simulated) {
    }

    /** The policy that decided one arm, or {@code null} on the arm where nothing matched. */
    public record MatchedPolicy(RoutingSimulationOutcome outcome, UUID policyId, String policyName,
                                boolean draft, Integer requiredApprovals) {
    }
}
