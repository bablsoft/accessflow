package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.PolicySimulationLimits;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SimulationWindow;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyDraft;
import com.bablsoft.accessflow.workflow.api.RoutingPolicySimulationService;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationOutcome;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationResult;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationResult.MatchedPolicy;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationResult.OutcomeDelta;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationResult.Sample;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationResult.UserImpact;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Replays historical traffic against the organization's current routing policies and against those
 * policies with a draft applied, and reports the diff (issue AF-630).
 *
 * <p>Both arms are evaluated from the <em>same</em> {@link ConditionContext} per row, so every
 * signal a replay cannot reconstruct faithfully — current group membership, today's anomaly state —
 * is identical on both sides and cancels out of the diff. Those approximations are still reported
 * as caveats rather than hidden.
 *
 * <p>Policies are resolved per the row's own datasource, because that is what routing does: an
 * org-wide corpus spans datasources, and a datasource-scoped policy must not be applied to a query
 * that never touched it.
 */
@Service
@RequiredArgsConstructor
class DefaultRoutingPolicySimulationService implements RoutingPolicySimulationService {

    private final QueryRequestLookupService queryRequestLookupService;
    private final ConditionContextFactory conditionContextFactory;
    private final RoutingPolicyEngine routingPolicyEngine;
    private final PolicySimulationLimits limits;

    // Routing's time-of-day / day-of-week operands evaluate in the server's local zone; the replay
    // must use the same zone or a policy would match different rows than it does in production.
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public RoutingSimulationResult simulate(UUID organizationId, SimulationWindow window,
                                            UUID corpusDatasourceId, RoutingPolicyDraft draft) {
        window.validate(limits.maxWindow());
        var run = new Run(organizationId, draft);
        var filter = new QueryListFilter(organizationId, null, corpusDatasourceId, null, null,
                window.from(), window.to());
        int cap = limits.maxRows();
        int scanned = queryRequestLookupService.streamCorpusForOrganization(filter, cap + 1, run::accept);
        return run.toResult(window, corpusDatasourceId, scanned > cap);
    }

    /** One simulation's mutable state: per-datasource policy sets, counters and the drill-down. */
    private final class Run {

        private final UUID organizationId;
        private final RoutingPolicyDraft draft;
        private final Map<UUID, PolicySets> policySetsByDatasource = new HashMap<>();
        private final Map<DeltaKey, Integer> deltas = new LinkedHashMap<>();
        private final Map<UUID, UserAccumulator> users = new LinkedHashMap<>();
        private final List<Sample> samples = new ArrayList<>();
        private boolean anyPolicyReadsAnomaly;
        private int evaluated;
        private int changed;

        private Run(UUID organizationId, RoutingPolicyDraft draft) {
            this.organizationId = organizationId;
            this.draft = draft;
        }

        void accept(QueryCorpusRow row) {
            if (evaluated >= limits.maxRows()) {
                return; // the cap+1 probe row: counted as truncation, never evaluated
            }
            evaluated++;
            var sets = policySetsByDatasource.computeIfAbsent(row.datasourceId(), this::policySetsFor);
            var context = conditionContextFactory.forHistoricalRow(row, clock.getZone());
            var baseline = routingPolicyEngine.firstMatch(sets.baseline(), context);
            var simulated = routingPolicyEngine.firstMatch(sets.simulated(), context);
            record(row, baseline, simulated);
        }

        private PolicySets policySetsFor(UUID datasourceId) {
            var baseline = routingPolicyEngine.enabledFor(organizationId, datasourceId);
            anyPolicyReadsAnomaly |= baseline.stream()
                    .anyMatch(policy -> readsAnomaly(policy.condition()));
            return new PolicySets(baseline, applyDraft(baseline, datasourceId));
        }

        /**
         * The set this datasource would have if the draft were saved: the replaced policy removed,
         * the draft inserted in priority order. A draft scoped to another datasource, or a disabled
         * one, contributes nothing — which is exactly what saving it would mean.
         */
        private List<EvaluablePolicy> applyDraft(List<EvaluablePolicy> baseline, UUID datasourceId) {
            if (draft == null) {
                return baseline;
            }
            var simulated = new ArrayList<EvaluablePolicy>(baseline.size() + 1);
            for (var policy : baseline) {
                if (!policy.id().equals(draft.replacesPolicyId())) {
                    simulated.add(policy);
                }
            }
            boolean scopeApplies = draft.datasourceId() == null
                    || draft.datasourceId().equals(datasourceId);
            if (draft.enabled() && scopeApplies) {
                anyPolicyReadsAnomaly |= readsAnomaly(draft.condition());
                simulated.add(new EvaluablePolicy(draft.replacesPolicyId(), draft.name(),
                        draft.priority(), draft.action(), draft.requiredApprovals(), draft.reason(),
                        draft.condition(), true));
            }
            // Stable sort on priority: an existing policy at the same priority still wins, which is
            // the conservative reading of a conflict the save path would reject with a 409.
            simulated.sort(Comparator.comparingInt(EvaluablePolicy::priority));
            return simulated;
        }

        private void record(QueryCorpusRow row, Optional<EvaluablePolicy> baseline,
                            Optional<EvaluablePolicy> simulated) {
            var baselineOutcome = outcomeOf(baseline);
            var simulatedOutcome = outcomeOf(simulated);
            if (baselineOutcome == simulatedOutcome && sameMatch(baseline, simulated)) {
                return;
            }
            changed++;
            deltas.merge(new DeltaKey(baselineOutcome, simulatedOutcome), 1, Integer::sum);
            var user = users.computeIfAbsent(row.submittedByUserId(),
                    id -> new UserAccumulator(row.submittedByEmail(), row.submittedByDisplayName()));
            user.changed++;
            user.outcomes.add(simulatedOutcome);
            if (samples.size() < limits.maxSamples()) {
                samples.add(new Sample(row.id(), row.submittedByEmail(), row.datasourceName(),
                        row.queryType(), row.status(), row.createdAt(),
                        matchedPolicy(baseline), matchedPolicy(simulated)));
            }
        }

        /**
         * Two matches with the same action still differ when a different policy decided it — the
         * admin needs to see that their draft took over a decision another rule used to make.
         */
        private boolean sameMatch(Optional<EvaluablePolicy> baseline,
                                  Optional<EvaluablePolicy> simulated) {
            if (baseline.isEmpty() && simulated.isEmpty()) {
                return true;
            }
            if (baseline.isEmpty() || simulated.isEmpty()) {
                return false;
            }
            return !simulated.get().draft()
                    && java.util.Objects.equals(baseline.get().id(), simulated.get().id());
        }

        RoutingSimulationResult toResult(SimulationWindow window, UUID datasourceId,
                                         boolean truncated) {
            var outcomeDeltas = deltas.entrySet().stream()
                    .map(e -> new OutcomeDelta(e.getKey().baseline(), e.getKey().simulated(),
                            e.getValue()))
                    .sorted(Comparator.comparingInt(OutcomeDelta::count).reversed())
                    .toList();
            var userImpacts = users.entrySet().stream()
                    .map(e -> new UserImpact(e.getKey(), e.getValue().email, e.getValue().displayName,
                            e.getValue().changed, List.copyOf(e.getValue().outcomes)))
                    .sorted(Comparator.comparingInt(UserImpact::changedCount).reversed())
                    .limit(limits.maxUserImpacts())
                    .toList();
            var caveats = new ArrayList<SimulationCaveat>();
            caveats.add(SimulationCaveat.MEMBERSHIP_STATE_CURRENT);
            if (anyPolicyReadsAnomaly) {
                // Only worth naming when a policy actually reads the signal we could not replay.
                caveats.add(SimulationCaveat.ANOMALY_STATE_CURRENT);
            }
            return new RoutingSimulationResult(window.from(), window.to(), datasourceId, evaluated,
                    changed, truncated, outcomeDeltas, userImpacts, samples, caveats);
        }
    }

    private record PolicySets(List<EvaluablePolicy> baseline, List<EvaluablePolicy> simulated) {
    }

    private record DeltaKey(RoutingSimulationOutcome baseline, RoutingSimulationOutcome simulated) {
    }

    private static final class UserAccumulator {
        private final String email;
        private final String displayName;
        private int changed;
        private final EnumSet<RoutingSimulationOutcome> outcomes =
                EnumSet.noneOf(RoutingSimulationOutcome.class);

        private UserAccumulator(String email, String displayName) {
            this.email = email;
            this.displayName = displayName;
        }
    }

    private static MatchedPolicy matchedPolicy(Optional<EvaluablePolicy> match) {
        return match.map(policy -> new MatchedPolicy(outcomeOf(policy.action()),
                        policy.draft() ? null : policy.id(), policy.name(), policy.draft(),
                        policy.requiredApprovals()))
                .orElse(null);
    }

    private static RoutingSimulationOutcome outcomeOf(Optional<EvaluablePolicy> match) {
        return match.map(policy -> outcomeOf(policy.action()))
                .orElse(RoutingSimulationOutcome.NO_MATCH);
    }

    private static RoutingSimulationOutcome outcomeOf(RoutingAction action) {
        return switch (action) {
            case AUTO_APPROVE -> RoutingSimulationOutcome.AUTO_APPROVE;
            case AUTO_REJECT -> RoutingSimulationOutcome.AUTO_REJECT;
            case REQUIRE_APPROVALS -> RoutingSimulationOutcome.REQUIRE_APPROVALS;
            case ESCALATE -> RoutingSimulationOutcome.ESCALATE;
        };
    }

    /** True when a condition tree reads the behavioural-anomaly signal a replay cannot reconstruct. */
    private static boolean readsAnomaly(ConditionNode node) {
        return switch (node) {
            case null -> false;
            case ConditionNode.AnomalyDetected ignored -> true;
            case ConditionNode.And and -> and.children().stream()
                    .anyMatch(DefaultRoutingPolicySimulationService::readsAnomaly);
            case ConditionNode.Or or -> or.children().stream()
                    .anyMatch(DefaultRoutingPolicySimulationService::readsAnomaly);
            case ConditionNode.Not not -> readsAnomaly(not.child());
            default -> false;
        };
    }
}
