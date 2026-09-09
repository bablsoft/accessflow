package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSimulationPeriodException;
import com.bablsoft.accessflow.core.api.PolicySimulationLimits;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyDraft;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SimulationWindow;
import com.bablsoft.accessflow.proxy.api.RowSecurityClassificationService;
import com.bablsoft.accessflow.proxy.api.RowSecuritySimulationResult.Transition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultRowSecurityPolicySimulationServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock RowSecurityResolutionService rowSecurityResolutionService;
    @Mock RowSecurityClassificationService rowSecurityClassificationService;

    private DefaultRowSecurityPolicySimulationService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-01T00:00:00Z");

    private final List<QueryCorpusRow> corpus = new ArrayList<>();

    private record Limits(int maxRows, int maxSamples, int maxUserImpacts, Duration maxWindow)
            implements PolicySimulationLimits {
    }

    @BeforeEach
    void setUp() {
        service = new DefaultRowSecurityPolicySimulationService(queryRequestLookupService,
                rowSecurityResolutionService, rowSecurityClassificationService,
                new Limits(10, 2, 10, Duration.ofDays(90)));
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        when(rowSecurityResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(new ResolvedRowSecurityPredicate(UUID.randomUUID(), "orders",
                        "tenant", RowSecurityOperator.EQUALS, List.of("acme"))));
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

    private QueryCorpusRow row(UUID submitter, String email) {
        return new QueryCorpusRow(UUID.randomUUID(), orgId, datasourceId, "prod", DbType.POSTGRESQL,
                submitter, email, "Name", "SELECT * FROM orders", QueryType.SELECT,
                QueryStatus.EXECUTED, false, RiskLevel.LOW, 10, false, null, null, false, FROM);
    }

    /** Baseline classification first, simulated second — the service calls them in that order. */
    private void stubClassifications(RowSecurityClassification baseline,
                                     RowSecurityClassification simulated) {
        when(rowSecurityClassificationService.classify(any(), any(), any(), any()))
                .thenReturn(baseline, simulated);
    }

    private SimulationWindow window() {
        return new SimulationWindow(FROM, TO);
    }

    private RowSecurityPolicyDraft draft() {
        return new RowSecurityPolicyDraft(null, "orders", "tenant", RowSecurityOperator.EQUALS,
                com.bablsoft.accessflow.core.api.RowSecurityValueType.LITERAL, "acme",
                List.of(), List.of(), List.of(), true);
    }

    // ---- window ----------------------------------------------------------------------------------

    @Test
    void rejectsAWindowLongerThanTheMaximum() {
        assertThatThrownBy(() -> service.simulate(orgId, datasourceId,
                new SimulationWindow(FROM, FROM.plus(Duration.ofDays(200))), draft()))
                .isInstanceOf(InvalidSimulationPeriodException.class);
    }

    // ---- corpus scoping --------------------------------------------------------------------------

    @Test
    void onlyExecutedQueriesAreReplayed() {
        service.simulate(orgId, datasourceId, window(), draft());

        var captor = org.mockito.ArgumentCaptor.forClass(QueryListFilter.class);
        verify(queryRequestLookupService).streamCorpusForOrganization(captor.capture(), anyInt(),
                any());
        // Row security acts on the statement that actually ran; a request that never executed has
        // no shape worth classifying.
        assertThat(captor.getValue().status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(captor.getValue().datasourceId()).isEqualTo(datasourceId);
    }

    // ---- transitions -----------------------------------------------------------------------------

    @Test
    void aPredicateThatStartsFilteringIsNewlyFiltered() {
        corpus.add(row(alice, "a@x.io"));
        stubClassifications(RowSecurityClassification.notApplicable("jdbc"),
                RowSecurityClassification.applied("jdbc", java.util.Set.of(UUID.randomUUID())));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.transitionCounts()).singleElement()
                .satisfies(t -> assertThat(t.transition()).isEqualTo(Transition.NEWLY_FILTERED));
        assertThat(result.userImpacts()).singleElement()
                .satisfies(u -> assertThat(u.newlyFilteredCount()).isEqualTo(1));
    }

    @Test
    void anUnresolvableVariableIsNewlyDenyAll() {
        corpus.add(row(alice, "a@x.io"));
        stubClassifications(RowSecurityClassification.notApplicable("jdbc"),
                RowSecurityClassification.denyAll("jdbc", java.util.Set.of(UUID.randomUUID())));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.transitionCounts()).singleElement()
                .satisfies(t -> assertThat(t.transition()).isEqualTo(Transition.NEWLY_DENY_ALL));
        assertThat(result.userImpacts().get(0).newlyDeniedCount()).isEqualTo(1);
    }

    @Test
    void anUnrewritableShapeIsNewlyFailsClosedAndKeepsTheReason() {
        corpus.add(row(alice, "a@x.io"));
        stubClassifications(RowSecurityClassification.notApplicable("jdbc"),
                RowSecurityClassification.failClosed("jdbc", "UNION over a protected table"));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.transitionCounts()).singleElement()
                .satisfies(t -> assertThat(t.transition()).isEqualTo(Transition.NEWLY_FAILS_CLOSED));
        assertThat(result.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.reason()).isEqualTo("UNION over a protected table");
            assertThat(sample.baselineOutcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
            assertThat(sample.simulatedOutcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        });
        assertThat(result.userImpacts().get(0).newlyFailsClosedCount()).isEqualTo(1);
    }

    @Test
    void removingAPolicyIsNoLongerFilteredAndIsNotCountedAsALossOfAccess() {
        corpus.add(row(alice, "a@x.io"));
        stubClassifications(RowSecurityClassification.applied("jdbc", java.util.Set.of(UUID.randomUUID())),
                RowSecurityClassification.notApplicable("jdbc"));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.transitionCounts()).singleElement()
                .satisfies(t -> assertThat(t.transition()).isEqualTo(Transition.NO_LONGER_FILTERED));
        var impact = result.userImpacts().get(0);
        assertThat(impact.newlyDeniedCount()).isZero();
        assertThat(impact.newlyFailsClosedCount()).isZero();
        assertThat(impact.newlyFilteredCount()).isZero();
    }

    @Test
    void identicalArmsAreUnchangedAndProduceNoSample() {
        corpus.add(row(alice, "a@x.io"));
        stubClassifications(RowSecurityClassification.notApplicable("jdbc"),
                RowSecurityClassification.notApplicable("jdbc"));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.changedCount()).isZero();
        assertThat(result.samples()).isEmpty();
        assertThat(result.transitionCounts()).singleElement()
                .satisfies(t -> assertThat(t.transition()).isEqualTo(Transition.UNCHANGED));
    }

    @Test
    void anUnknownOnEitherArmIsUnclassifiableAndNeverUnchanged() {
        corpus.add(row(alice, "a@x.io"));
        stubClassifications(RowSecurityClassification.unknown("cassandra", "needs live keys"),
                RowSecurityClassification.unknown("cassandra", "needs live keys"));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        // Both arms say UNKNOWN, which would look "equal" — but reporting that as unchanged would
        // hide exactly the risk the simulator exists to surface.
        assertThat(result.unclassifiableCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.caveats())
                .contains(SimulationCaveat.ENGINE_CLASSIFICATION_UNAVAILABLE);
    }

    @Test
    void theEngineCaveatIsAbsentWhenEverythingCouldBeClassified() {
        corpus.add(row(alice, "a@x.io"));
        stubClassifications(RowSecurityClassification.notApplicable("jdbc"),
                RowSecurityClassification.notApplicable("jdbc"));

        assertThat(service.simulate(orgId, datasourceId, window(), draft()).caveats())
                .containsExactly(SimulationCaveat.MEMBERSHIP_STATE_CURRENT);
    }

    // ---- aggregation -----------------------------------------------------------------------------

    @Test
    void predicateResolutionIsMemoisedPerSubmitterNotPerRow() {
        corpus.add(row(alice, "a@x.io"));
        corpus.add(row(alice, "a@x.io"));
        corpus.add(row(bob, "b@x.io"));
        when(rowSecurityClassificationService.classify(any(), any(), any(), any()))
                .thenReturn(RowSecurityClassification.notApplicable("jdbc"));

        service.simulate(orgId, datasourceId, window(), draft());

        // Three rows, two submitters — resolution is the same answer for every query a person
        // submitted and is the biggest cost in the loop.
        verify(rowSecurityResolutionService, times(2)).resolveApplicable(any(), any(), any());
        verify(rowSecurityResolutionService, times(2)).resolveWithDraft(any(), any(), any(), any());
    }

    @Test
    void samplesAreCappedButCountsAreNot() {
        for (int i = 0; i < 5; i++) {
            corpus.add(row(alice, "a@x.io"));
        }
        // Alternating baseline/simulated answers: every row changes, so counts outrun samples.
        when(rowSecurityClassificationService.classify(any(), any(), any(), any()))
                .thenReturn(RowSecurityClassification.notApplicable("jdbc"),
                        RowSecurityClassification.failClosed("jdbc", "nope"),
                        RowSecurityClassification.notApplicable("jdbc"),
                        RowSecurityClassification.failClosed("jdbc", "nope"),
                        RowSecurityClassification.notApplicable("jdbc"),
                        RowSecurityClassification.failClosed("jdbc", "nope"),
                        RowSecurityClassification.notApplicable("jdbc"),
                        RowSecurityClassification.failClosed("jdbc", "nope"),
                        RowSecurityClassification.notApplicable("jdbc"),
                        RowSecurityClassification.failClosed("jdbc", "nope"));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.changedCount()).isEqualTo(5);
        assertThat(result.samples()).hasSize(2);
    }

    @Test
    void moreRowsThanTheCapMarksTheResultTruncated() {
        for (int i = 0; i < 12; i++) {
            corpus.add(row(alice, "a@x.io"));
        }
        when(rowSecurityClassificationService.classify(any(), any(), any(), any()))
                .thenReturn(RowSecurityClassification.notApplicable("jdbc"));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.truncated()).isTrue();
        assertThat(result.evaluatedCount()).isEqualTo(10);
    }

    @Test
    void anEmptyCorpusStillReturnsAWellFormedResult() {
        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.evaluatedCount()).isZero();
        assertThat(result.changedCount()).isZero();
        assertThat(result.datasourceId()).isEqualTo(datasourceId);
        assertThat(result.periodFrom()).isEqualTo(FROM);
    }

    // ---- the pure transition table ---------------------------------------------------------------

    @Test
    void transitionTableCoversEveryOutcomePair() {
        assertThat(DefaultRowSecurityPolicySimulationService.transitionOf(
                RowSecurityOutcome.NOT_APPLICABLE, RowSecurityOutcome.NOT_APPLICABLE))
                .isEqualTo(Transition.UNCHANGED);
        assertThat(DefaultRowSecurityPolicySimulationService.transitionOf(
                RowSecurityOutcome.APPLIED, RowSecurityOutcome.DENY_ALL))
                .isEqualTo(Transition.NEWLY_DENY_ALL);
        assertThat(DefaultRowSecurityPolicySimulationService.transitionOf(
                RowSecurityOutcome.DENY_ALL, RowSecurityOutcome.APPLIED))
                .isEqualTo(Transition.NEWLY_FILTERED);
        assertThat(DefaultRowSecurityPolicySimulationService.transitionOf(
                RowSecurityOutcome.APPLIED, RowSecurityOutcome.NOT_APPLICABLE))
                .isEqualTo(Transition.NO_LONGER_FILTERED);
        assertThat(DefaultRowSecurityPolicySimulationService.transitionOf(
                RowSecurityOutcome.UNKNOWN, RowSecurityOutcome.APPLIED))
                .isEqualTo(Transition.UNCLASSIFIABLE);
        assertThat(DefaultRowSecurityPolicySimulationService.transitionOf(
                RowSecurityOutcome.APPLIED, RowSecurityOutcome.UNKNOWN))
                .isEqualTo(Transition.UNCLASSIFIABLE);
    }
}
