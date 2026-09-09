package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSimulationPeriodException;
import com.bablsoft.accessflow.core.api.PolicySimulationLimits;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SimulationWindow;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyDraft;
import com.bablsoft.accessflow.workflow.api.RoutingSimulationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultRoutingPolicySimulationServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ConditionContextFactory conditionContextFactory;
    @Mock RoutingPolicyEngine routingPolicyEngine;

    private DefaultRoutingPolicySimulationService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID otherDatasourceId = UUID.randomUUID();
    private final UUID existingPolicyId = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-01T00:00:00Z");

    private final List<QueryCorpusRow> corpus = new ArrayList<>();

    /** Test limits: small caps so truncation and sample capping are reachable. */
    private record Limits(int maxRows, int maxSamples, int maxUserImpacts, Duration maxWindow)
            implements PolicySimulationLimits {
    }

    @BeforeEach
    void setUp() {
        service = new DefaultRoutingPolicySimulationService(queryRequestLookupService,
                conditionContextFactory, routingPolicyEngine,
                new Limits(10, 2, 10, Duration.ofDays(90)));
        service.setClock(Clock.fixed(FROM, ZoneId.of("UTC")));

        when(conditionContextFactory.forHistoricalRow(any(), any())).thenReturn(context());
        when(routingPolicyEngine.enabledFor(any(), any())).thenReturn(List.of());
        // Drive the consumer over whatever `corpus` holds and report how many rows were seen.
        when(queryRequestLookupService.streamCorpusForOrganization(any(QueryListFilter.class),
                anyInt(), any()))
                .thenAnswer(invocation -> {
                    Consumer<QueryCorpusRow> consumer = invocation.getArgument(2);
                    int cap = invocation.getArgument(1);
                    int seen = 0;
                    for (var row : corpus) {
                        if (seen >= cap) {
                            break;
                        }
                        consumer.accept(row);
                        seen++;
                    }
                    return seen;
                });
    }

    private static ConditionContext context() {
        return new ConditionContext(QueryType.SELECT, Set.of("orders"), RiskLevel.LOW, 10, "ANALYST",
                Set.of(), LocalDateTime.of(2026, 6, 15, 9, 0), true, false, false,
                "10.0.0.1", "psql", false, null, false, null, null);
    }

    private QueryCorpusRow row(UUID submitter, String email, UUID dsId) {
        return row(submitter, email, dsId, false);
    }

    private QueryCorpusRow row(UUID submitter, String email, UUID dsId, boolean aiFailed) {
        return new QueryCorpusRow(UUID.randomUUID(), orgId, dsId, "prod", DbType.POSTGRESQL,
                submitter, email, "Name", "SELECT * FROM orders", QueryType.SELECT,
                QueryStatus.EXECUTED, false, RiskLevel.LOW, 10, aiFailed, null, null, false, FROM);
    }

    private EvaluablePolicy policy(UUID id, String name, int priority, RoutingAction action,
                                   boolean draft) {
        return new EvaluablePolicy(id, name, priority, action, null, "because",
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)), draft);
    }

    private RoutingPolicyDraft draft(UUID replaces, UUID dsId, boolean enabled, RoutingAction action) {
        return new RoutingPolicyDraft(replaces, "Draft", dsId, 10, enabled,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)), action, null, "drafted");
    }

    private SimulationWindow window() {
        return new SimulationWindow(FROM, TO);
    }

    /** Makes firstMatch return `baseline` for the baseline list and `simulated` for the other. */
    private void stubMatches(Optional<EvaluablePolicy> baseline, Optional<EvaluablePolicy> simulated) {
        when(routingPolicyEngine.firstMatch(any(), any())).thenAnswer(invocation -> {
            List<EvaluablePolicy> policies = invocation.getArgument(0);
            boolean hasDraft = policies.stream().anyMatch(EvaluablePolicy::draft);
            return hasDraft ? simulated : baseline;
        });
    }

    // ---- window bounds --------------------------------------------------------------------------

    @Test
    void rejectsAWindowLongerThanTheConfiguredMaximum() {
        assertThatThrownBy(() -> service.simulate(orgId,
                new SimulationWindow(FROM, FROM.plus(Duration.ofDays(200))), null, null))
                .isInstanceOf(InvalidSimulationPeriodException.class);
    }

    @Test
    void rejectsAnInvertedWindow() {
        assertThatThrownBy(() -> service.simulate(orgId, new SimulationWindow(TO, FROM), null, null))
                .isInstanceOf(InvalidSimulationPeriodException.class);
    }

    // ---- the A/B ---------------------------------------------------------------------------------

    @Test
    void identicalArmsReportNoChange() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        stubMatches(Optional.empty(), Optional.empty());

        var result = service.simulate(orgId, window(), null, null);

        assertThat(result.evaluatedCount()).isEqualTo(1);
        assertThat(result.changedCount()).isZero();
        assertThat(result.outcomeDeltas()).isEmpty();
        assertThat(result.userImpacts()).isEmpty();
        assertThat(result.samples()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aDraftThatNewlyMatchesIsReportedAsNoMatchToItsAction() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        corpus.add(row(alice, "a@x.io", datasourceId));
        var drafted = policy(null, "Draft", 10, RoutingAction.AUTO_REJECT, true);
        stubMatches(Optional.empty(), Optional.of(drafted));

        var result = service.simulate(orgId, window(), null,
                draft(null, null, true, RoutingAction.AUTO_REJECT));

        assertThat(result.changedCount()).isEqualTo(2);
        assertThat(result.outcomeDeltas()).singleElement().satisfies(delta -> {
            assertThat(delta.baseline()).isEqualTo(RoutingSimulationOutcome.NO_MATCH);
            assertThat(delta.simulated()).isEqualTo(RoutingSimulationOutcome.AUTO_REJECT);
            assertThat(delta.count()).isEqualTo(2);
        });
    }

    @Test
    void aDraftTakingOverADecisionFromAnotherPolicyCountsAsAChange() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        var existing = policy(existingPolicyId, "Existing", 20, RoutingAction.AUTO_REJECT, false);
        var drafted = policy(null, "Draft", 10, RoutingAction.AUTO_REJECT, true);
        stubMatches(Optional.of(existing), Optional.of(drafted));

        var result = service.simulate(orgId, window(), null,
                draft(null, null, true, RoutingAction.AUTO_REJECT));

        // Same action, different rule — the admin needs to see their draft took the decision over.
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.baseline().policyId()).isEqualTo(existingPolicyId);
            assertThat(sample.baseline().draft()).isFalse();
            assertThat(sample.simulated().draft()).isTrue();
            assertThat(sample.simulated().policyId()).isNull();
        });
    }

    @Test
    void samplesCarryNoSqlText() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        stubMatches(Optional.empty(),
                Optional.of(policy(null, "Draft", 10, RoutingAction.AUTO_REJECT, true)));

        var sample = service.simulate(orgId, window(), null,
                draft(null, null, true, RoutingAction.AUTO_REJECT)).samples().get(0);

        // ROUTING_POLICY_MANAGE does not grant read access to other people's queries; the query id
        // is the only handle a simulation hands out.
        assertThat(sample.queryRequestId()).isNotNull();
        assertThat(sample.submittedByEmail()).isEqualTo("a@x.io");
        assertThat(sample).hasNoNullFieldsOrPropertiesExcept("baseline");
    }

    // ---- aggregation -----------------------------------------------------------------------------

    @Test
    void userImpactsAreOrderedByBlastRadiusDescending() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        corpus.add(row(alice, "a@x.io", datasourceId));
        corpus.add(row(bob, "b@x.io", datasourceId));
        stubMatches(Optional.empty(),
                Optional.of(policy(null, "Draft", 10, RoutingAction.AUTO_REJECT, true)));

        var result = service.simulate(orgId, window(), null,
                draft(null, null, true, RoutingAction.AUTO_REJECT));

        assertThat(result.userImpacts()).extracting("email").containsExactly("a@x.io", "b@x.io");
        assertThat(result.userImpacts().get(0).changedCount()).isEqualTo(2);
        assertThat(result.userImpacts().get(0).simulatedOutcomes())
                .containsExactly(RoutingSimulationOutcome.AUTO_REJECT);
    }

    @Test
    void samplesAreCappedButCountsAreNot() {
        for (int i = 0; i < 5; i++) {
            corpus.add(row(alice, "a@x.io", datasourceId));
        }
        stubMatches(Optional.empty(),
                Optional.of(policy(null, "Draft", 10, RoutingAction.AUTO_REJECT, true)));

        var result = service.simulate(orgId, window(), null,
                draft(null, null, true, RoutingAction.AUTO_REJECT));

        assertThat(result.changedCount()).isEqualTo(5);
        assertThat(result.samples()).hasSize(2); // maxSamples
    }

    @Test
    void moreRowsThanTheCapMarksTheResultTruncatedAndStopsEvaluating() {
        for (int i = 0; i < 12; i++) {
            corpus.add(row(alice, "a@x.io", datasourceId));
        }
        stubMatches(Optional.empty(), Optional.empty());

        var result = service.simulate(orgId, window(), null, null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.evaluatedCount()).isEqualTo(10); // maxRows, not the cap+1 probe
    }

    // ---- draft scoping ---------------------------------------------------------------------------

    @Test
    void aDraftScopedToAnotherDatasourceDoesNotTouchThisOnesRows() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        when(routingPolicyEngine.firstMatch(any(), any())).thenAnswer(invocation -> {
            List<EvaluablePolicy> policies = invocation.getArgument(0);
            return policies.stream().filter(EvaluablePolicy::draft).findFirst();
        });

        var result = service.simulate(orgId, window(), null,
                draft(null, otherDatasourceId, true, RoutingAction.AUTO_REJECT));

        assertThat(result.changedCount()).isZero();
    }

    @Test
    void aDisabledDraftContributesNothing() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        when(routingPolicyEngine.firstMatch(any(), any())).thenAnswer(invocation -> {
            List<EvaluablePolicy> policies = invocation.getArgument(0);
            return policies.stream().filter(EvaluablePolicy::draft).findFirst();
        });

        var result = service.simulate(orgId, window(), null,
                draft(null, null, false, RoutingAction.AUTO_REJECT));

        assertThat(result.changedCount()).isZero();
    }

    // ---- caveats ---------------------------------------------------------------------------------

    @Test
    void membershipCaveatIsAlwaysReported() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        stubMatches(Optional.empty(), Optional.empty());

        assertThat(service.simulate(orgId, window(), null, null).caveats())
                .containsExactly(SimulationCaveat.MEMBERSHIP_STATE_CURRENT);
    }

    @Test
    void anomalyCaveatIsReportedOnlyWhenAPolicyActuallyReadsTheSignal() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        stubMatches(Optional.empty(), Optional.empty());
        var anomalyDraft = new RoutingPolicyDraft(null, "Draft", null, 10, true,
                new ConditionNode.And(List.of(new ConditionNode.AnomalyDetected(true))),
                RoutingAction.ESCALATE, 1, "escalate anomalous");

        var result = service.simulate(orgId, window(), null, anomalyDraft);

        assertThat(result.caveats()).contains(SimulationCaveat.ANOMALY_STATE_CURRENT);
    }

    @Test
    void anomalyCaveatComesFromAnExistingPolicyToo() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        when(routingPolicyEngine.enabledFor(any(), any())).thenReturn(List.of(
                new EvaluablePolicy(existingPolicyId, "Anomalous", 5, RoutingAction.ESCALATE, 1,
                        "r", new ConditionNode.Not(new ConditionNode.AnomalyDetected(false)), false)));
        stubMatches(Optional.empty(), Optional.empty());

        assertThat(service.simulate(orgId, window(), null, null).caveats())
                .contains(SimulationCaveat.ANOMALY_STATE_CURRENT);
    }

    @Test
    void anEmptyCorpusStillReturnsAWellFormedResult() {
        stubMatches(Optional.empty(), Optional.empty());

        var result = service.simulate(orgId, window(), datasourceId, null);

        assertThat(result.evaluatedCount()).isZero();
        assertThat(result.changedCount()).isZero();
        assertThat(result.datasourceId()).isEqualTo(datasourceId);
        assertThat(result.periodFrom()).isEqualTo(FROM);
        assertThat(result.periodTo()).isEqualTo(TO);
    }

    // ---- rows production never routes -------------------------------------------------------------

    @Test
    void aiFailedRowsAreExcludedBecauseProductionNeverRoutesThem() {
        corpus.add(row(alice, "a@x.io", datasourceId, true));
        corpus.add(row(alice, "a@x.io", datasourceId, false));
        stubMatches(Optional.empty(),
                Optional.of(policy(null, "Draft", 10, RoutingAction.AUTO_REJECT, true)));

        var result = service.simulate(orgId, window(), null,
                draft(null, null, true, RoutingAction.AUTO_REJECT));

        // QueryReviewStateMachine.onAiFailed goes straight to PENDING_REVIEW without consulting
        // routing, so counting that row would credit the draft with traffic it never sees.
        assertThat(result.evaluatedCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.skippedAiFailedCount()).isEqualTo(1);
    }

    @Test
    void aCorpusOfOnlyAiFailedRowsEvaluatesNothing() {
        corpus.add(row(alice, "a@x.io", datasourceId, true));
        stubMatches(Optional.empty(), Optional.empty());

        var result = service.simulate(orgId, window(), null, null);

        assertThat(result.evaluatedCount()).isZero();
        assertThat(result.skippedAiFailedCount()).isEqualTo(1);
    }

    // ---- editing a policy without changing its effect ---------------------------------------------

    @Test
    void replacingAPolicyWithoutChangingItsEffectIsNotAChange() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        var existing = policy(existingPolicyId, "Existing", 20, RoutingAction.AUTO_REJECT, false);
        // A replacing draft keeps the id it replaces; only its name/reason differ here.
        var renamed = policy(existingPolicyId, "Renamed", 20, RoutingAction.AUTO_REJECT, true);
        stubMatches(Optional.of(existing), Optional.of(renamed));

        var result = service.simulate(orgId, window(), null,
                draft(existingPolicyId, null, true, RoutingAction.AUTO_REJECT));

        // Editing only the label must not report 100% of the policy's matched traffic as changed.
        assertThat(result.changedCount()).isZero();
        assertThat(result.outcomeDeltas()).isEmpty();
    }

    @Test
    void replacingAPolicyAndChangingItsActionIsAChange() {
        corpus.add(row(alice, "a@x.io", datasourceId));
        var existing = policy(existingPolicyId, "Existing", 20, RoutingAction.ESCALATE, false);
        var edited = policy(existingPolicyId, "Existing", 20, RoutingAction.AUTO_REJECT, true);
        stubMatches(Optional.of(existing), Optional.of(edited));

        var result = service.simulate(orgId, window(), null,
                draft(existingPolicyId, null, true, RoutingAction.AUTO_REJECT));

        assertThat(result.changedCount()).isEqualTo(1);
    }
}
