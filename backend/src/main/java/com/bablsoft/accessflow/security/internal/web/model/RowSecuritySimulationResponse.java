package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shape for a row-security dry run (issue AF-630). */
public record RowSecuritySimulationResponse(
        Instant periodFrom,
        Instant periodTo,
        UUID datasourceId,
        int evaluatedCount,
        int changedCount,
        int unclassifiableCount,
        boolean truncated,
        List<TransitionCount> transitionCounts,
        List<UserImpact> userImpacts,
        List<Sample> samples,
        List<SimulationCaveat> caveats) {

    public static RowSecuritySimulationResponse from(RowSecuritySimulationResult result) {
        return new RowSecuritySimulationResponse(result.periodFrom(), result.periodTo(),
                result.datasourceId(), result.evaluatedCount(), result.changedCount(),
                result.unclassifiableCount(), result.truncated(),
                result.transitionCounts().stream()
                        .map(t -> new TransitionCount(t.transition().name(), t.count())).toList(),
                result.userImpacts().stream()
                        .map(u -> new UserImpact(u.userId(), u.email(), u.displayName(),
                                u.newlyFilteredCount(), u.newlyDeniedCount(),
                                u.newlyFailsClosedCount())).toList(),
                result.samples().stream()
                        .map(s -> new Sample(s.queryRequestId(), s.submittedByEmail(), s.queryType(),
                                s.createdAt(), s.transition().name(), s.baselineOutcome(),
                                s.simulatedOutcome(), s.reason())).toList(),
                result.caveats());
    }

    public record TransitionCount(String transition, int count) {
    }

    public record UserImpact(UUID userId, String email, String displayName, int newlyFilteredCount,
                             int newlyDeniedCount, int newlyFailsClosedCount) {
    }

    public record Sample(UUID queryRequestId, String submittedByEmail, QueryType queryType,
                         Instant createdAt, String transition, RowSecurityOutcome baselineOutcome,
                         RowSecurityOutcome simulatedOutcome, String reason) {
    }
}
