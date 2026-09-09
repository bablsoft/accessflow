package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationOutcome;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shape for a routing-policy dry run (issue AF-630). */
public record RoutingSimulationResponse(
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

    public static RoutingSimulationResponse from(RoutingSimulationResult result) {
        return new RoutingSimulationResponse(
                result.periodFrom(), result.periodTo(), result.datasourceId(),
                result.evaluatedCount(), result.changedCount(), result.truncated(),
                result.outcomeDeltas().stream()
                        .map(d -> new OutcomeDelta(d.baseline(), d.simulated(), d.count())).toList(),
                result.userImpacts().stream()
                        .map(u -> new UserImpact(u.userId(), u.email(), u.displayName(),
                                u.changedCount(), u.simulatedOutcomes())).toList(),
                result.samples().stream().map(Sample::from).toList(),
                result.caveats());
    }

    public record OutcomeDelta(RoutingSimulationOutcome baselineAction,
                               RoutingSimulationOutcome simulatedAction, int count) {
    }

    public record UserImpact(UUID userId, String email, String displayName, int changedCount,
                             List<RoutingSimulationOutcome> simulatedActions) {
    }

    public record Sample(UUID queryRequestId, String submittedByEmail, String datasourceName,
                         QueryType queryType, QueryStatus historicalStatus, Instant createdAt,
                         MatchedPolicy baseline, MatchedPolicy simulated) {

        static Sample from(RoutingSimulationResult.Sample sample) {
            return new Sample(sample.queryRequestId(), sample.submittedByEmail(),
                    sample.datasourceName(), sample.queryType(), sample.historicalStatus(),
                    sample.createdAt(), MatchedPolicy.from(sample.baseline()),
                    MatchedPolicy.from(sample.simulated()));
        }
    }

    public record MatchedPolicy(RoutingSimulationOutcome action, UUID policyId, String policyName,
                                boolean isDraft, Integer requiredApprovals) {

        static MatchedPolicy from(RoutingSimulationResult.MatchedPolicy matched) {
            return matched == null ? null
                    : new MatchedPolicy(matched.outcome(), matched.policyId(), matched.policyName(),
                            matched.draft(), matched.requiredApprovals());
        }
    }
}
