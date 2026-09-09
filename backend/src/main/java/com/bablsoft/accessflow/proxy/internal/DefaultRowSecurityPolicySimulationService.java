package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.PolicySimulationLimits;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyDraft;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SimulationWindow;
import com.bablsoft.accessflow.proxy.api.RowSecurityClassificationService;
import com.bablsoft.accessflow.proxy.api.RowSecurityPolicySimulationService;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult.Sample;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult.Transition;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult.TransitionCount;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult.UserImpact;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replays a datasource's executed queries under its current row-security policies and under those
 * policies with a draft applied, and reports the diff (issue AF-630).
 *
 * <p>Only executed queries are replayed: row security acts on the statement that actually ran, so a
 * request that never executed has no meaningful shape to classify.
 *
 * <p>Predicate resolution is memoised per submitter rather than per row — it is the same answer for
 * every query a person submitted, and it is the single biggest cost in the loop.
 */
@Service
@RequiredArgsConstructor
class DefaultRowSecurityPolicySimulationService implements RowSecurityPolicySimulationService {

    private final QueryRequestLookupService queryRequestLookupService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final RowSecurityClassificationService rowSecurityClassificationService;
    private final PolicySimulationLimits limits;

    @Override
    @Transactional(readOnly = true)
    public RowSecuritySimulationResult simulate(UUID organizationId, UUID datasourceId,
                                                SimulationWindow window,
                                                RowSecurityPolicyDraft draft) {
        window.validate(limits.maxWindow());
        var run = new Run(organizationId, datasourceId, draft);
        var filter = new QueryListFilter(organizationId, null, datasourceId, QueryStatus.EXECUTED,
                null, window.from(), window.to());
        int cap = limits.maxRows();
        int scanned = queryRequestLookupService.streamCorpusForOrganization(filter, cap + 1, run::accept);
        return run.toResult(window, scanned > cap);
    }

    private final class Run {

        private final UUID organizationId;
        private final UUID datasourceId;
        private final RowSecurityPolicyDraft draft;
        private final Map<UUID, ResolvedSets> predicatesBySubmitter = new HashMap<>();
        private final Map<Transition, Integer> transitions = new EnumMap<>(Transition.class);
        private final Map<UUID, UserAccumulator> users = new LinkedHashMap<>();
        private final List<Sample> samples = new ArrayList<>();
        private int evaluated;
        private int changed;
        private int unclassifiable;

        private Run(UUID organizationId, UUID datasourceId, RowSecurityPolicyDraft draft) {
            this.organizationId = organizationId;
            this.datasourceId = datasourceId;
            this.draft = draft;
        }

        void accept(QueryCorpusRow row) {
            if (evaluated >= limits.maxRows()) {
                return; // the cap+1 probe row: counted as truncation, never evaluated
            }
            evaluated++;
            var sets = predicatesBySubmitter.computeIfAbsent(row.submittedByUserId(), this::resolveFor);
            var baseline = classify(row, sets.baseline());
            var simulated = classify(row, sets.simulated());
            record(row, baseline, simulated);
        }

        private ResolvedSets resolveFor(UUID submitterId) {
            return new ResolvedSets(
                    toDirectives(rowSecurityResolutionService
                            .resolveApplicable(organizationId, datasourceId, submitterId)),
                    toDirectives(rowSecurityResolutionService
                            .resolveWithDraft(organizationId, datasourceId, submitterId, draft)));
        }

        private RowSecurityClassification classify(QueryCorpusRow row,
                                                    List<RowSecurityDirective> directives) {
            return rowSecurityClassificationService.classify(row.datasourceId(), row.dbType(),
                    row.sqlText(), directives);
        }

        private void record(QueryCorpusRow row, RowSecurityClassification baseline,
                            RowSecurityClassification simulated) {
            var transition = transitionOf(baseline.outcome(), simulated.outcome());
            transitions.merge(transition, 1, Integer::sum);
            if (transition == Transition.UNCLASSIFIABLE) {
                unclassifiable++;
            }
            if (transition == Transition.UNCHANGED) {
                return;
            }
            changed++;
            var user = users.computeIfAbsent(row.submittedByUserId(),
                    id -> new UserAccumulator(row.submittedByEmail(), row.submittedByDisplayName()));
            switch (transition) {
                case NEWLY_FILTERED -> user.newlyFiltered++;
                case NEWLY_DENY_ALL -> user.newlyDenied++;
                case NEWLY_FAILS_CLOSED -> user.newlyFailsClosed++;
                default -> { /* NO_LONGER_FILTERED and UNCLASSIFIABLE are not a loss of access */ }
            }
            if (samples.size() < limits.maxSamples()) {
                samples.add(new Sample(row.id(), row.submittedByEmail(), row.queryType(),
                        row.createdAt(), transition, baseline.outcome(), simulated.outcome(),
                        simulated.reason() != null ? simulated.reason() : baseline.reason()));
            }
        }

        RowSecuritySimulationResult toResult(SimulationWindow window, boolean truncated) {
            var counts = transitions.entrySet().stream()
                    .map(e -> new TransitionCount(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingInt(TransitionCount::count).reversed())
                    .toList();
            var impacts = users.entrySet().stream()
                    .map(e -> new UserImpact(e.getKey(), e.getValue().email, e.getValue().displayName,
                            e.getValue().newlyFiltered, e.getValue().newlyDenied,
                            e.getValue().newlyFailsClosed))
                    .sorted(Comparator.comparingInt(
                            (UserImpact u) -> u.newlyDeniedCount() + u.newlyFailsClosedCount()
                                    + u.newlyFilteredCount()).reversed())
                    .limit(limits.maxUserImpacts())
                    .toList();
            var caveats = new ArrayList<SimulationCaveat>();
            caveats.add(SimulationCaveat.MEMBERSHIP_STATE_CURRENT);
            if (unclassifiable > 0) {
                caveats.add(SimulationCaveat.ENGINE_CLASSIFICATION_UNAVAILABLE);
            }
            return new RowSecuritySimulationResult(window.from(), window.to(), datasourceId,
                    evaluated, changed, unclassifiable, truncated, counts, impacts, samples, caveats);
        }
    }

    /**
     * How a query's treatment moved. An {@code UNKNOWN} on either arm dominates: the honest answer
     * is that we could not tell, and reporting it as unchanged would hide exactly the risk the
     * simulator exists to surface.
     */
    static Transition transitionOf(RowSecurityOutcome baseline, RowSecurityOutcome simulated) {
        if (baseline == RowSecurityOutcome.UNKNOWN || simulated == RowSecurityOutcome.UNKNOWN) {
            return Transition.UNCLASSIFIABLE;
        }
        if (baseline == simulated) {
            return Transition.UNCHANGED;
        }
        return switch (simulated) {
            case FAIL_CLOSED -> Transition.NEWLY_FAILS_CLOSED;
            case DENY_ALL -> Transition.NEWLY_DENY_ALL;
            case APPLIED -> Transition.NEWLY_FILTERED;
            case NOT_APPLICABLE -> Transition.NO_LONGER_FILTERED;
            case UNKNOWN -> Transition.UNCLASSIFIABLE;
        };
    }

    private static List<RowSecurityDirective> toDirectives(
            List<ResolvedRowSecurityPredicate> predicates) {
        return predicates.stream()
                .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                        p.operator(), p.values()))
                .toList();
    }

    private record ResolvedSets(List<RowSecurityDirective> baseline,
                                List<RowSecurityDirective> simulated) {
    }

    private static final class UserAccumulator {
        private final String email;
        private final String displayName;
        private int newlyFiltered;
        private int newlyDenied;
        private int newlyFailsClosed;

        private UserAccumulator(String email, String displayName) {
            this.email = email;
            this.displayName = displayName;
        }
    }
}
