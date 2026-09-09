package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.SimulationCaveat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The diff between two classifications of the same historical corpus — once against the
 * datasource's current row-security policies, once with a draft applied (issue AF-630).
 *
 * @param unclassifiableCount rows whose engine could not answer offline. They are never counted as
 *                            unaffected; a caller that renders them as "no impact" defeats the
 *                            point of the feature.
 */
public record RowSecuritySimulationResult(
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

    public RowSecuritySimulationResult {
        transitionCounts = transitionCounts == null ? List.of() : List.copyOf(transitionCounts);
        userImpacts = userImpacts == null ? List.of() : List.copyOf(userImpacts);
        samples = samples == null ? List.of() : List.copyOf(samples);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }

    /** How one query's row-security treatment moves between the two arms. */
    public enum Transition {
        /** Both arms agree; the draft changes nothing for this query. */
        UNCHANGED,
        /** The draft starts filtering a query that was previously unfiltered. */
        NEWLY_FILTERED,
        /** The draft resolves to no values, so the submitter would see nothing. */
        NEWLY_DENY_ALL,
        /** The draft makes the query unrewritable, so it would be rejected outright. */
        NEWLY_FAILS_CLOSED,
        /** The draft removes filtering this query used to get. */
        NO_LONGER_FILTERED,
        /** At least one arm could not be classified offline. Never read this as safe. */
        UNCLASSIFIABLE
    }

    public record TransitionCount(Transition transition, int count) {
    }

    /** Per-submitter blast radius, ordered by total impact descending. */
    public record UserImpact(UUID userId, String email, String displayName, int newlyFilteredCount,
                             int newlyDeniedCount, int newlyFailsClosedCount) {
    }

    /**
     * One changed request. Carries no SQL text: {@code ROW_SECURITY_MANAGE} does not otherwise
     * grant read access to other people's queries.
     */
    public record Sample(UUID queryRequestId, String submittedByEmail, QueryType queryType,
                         Instant createdAt, Transition transition, RowSecurityOutcome baselineOutcome,
                         RowSecurityOutcome simulatedOutcome, String reason) {
    }
}
