package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSimulationPeriodException;
import com.bablsoft.accessflow.core.api.MaskingPolicyDraft;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.PolicySimulationLimits;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SimulationWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class DefaultMaskingPolicySimulationServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock MaskingPolicyResolutionService maskingPolicyResolutionService;
    @Mock QueryResultPersistenceService queryResultPersistenceService;

    private DefaultMaskingPolicySimulationService service;

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
        service = new DefaultMaskingPolicySimulationService(queryRequestLookupService,
                maskingPolicyResolutionService, queryResultPersistenceService,
                new Limits(10, 2, 10, Duration.ofDays(90)), new ObjectMapper());
        when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        stubColumns("email", "name");
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

    private void stubColumns(String... names) {
        var json = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"name\":\"").append(names[i]).append("\",\"jdbcType\":12}");
        }
        json.append("]");
        when(queryResultPersistenceService.find(any()))
                .thenReturn(Optional.of(new QueryResultPersistenceService.QueryResultSnapshot(
                        UUID.randomUUID(), json.toString(), "[]", 1, false, null, 5)));
    }

    private QueryCorpusRow row(UUID submitter, String email) {
        return new QueryCorpusRow(UUID.randomUUID(), orgId, datasourceId, "prod", DbType.POSTGRESQL,
                submitter, email, "Name", "SELECT email, name FROM customers", QueryType.SELECT,
                QueryStatus.EXECUTED, false, RiskLevel.LOW, 10, false, null, null, false, FROM);
    }

    private ResolvedColumnMask mask(String columnRef) {
        return new ResolvedColumnMask(UUID.randomUUID(), columnRef, MaskingStrategy.FULL, Map.of());
    }

    private MaskingPolicyDraft draft() {
        return new MaskingPolicyDraft(null, "customers.email", MaskingStrategy.FULL, Map.of(),
                List.of(), List.of(), List.of(), true);
    }

    private SimulationWindow window() {
        return new SimulationWindow(FROM, TO);
    }

    // ---- window + corpus -------------------------------------------------------------------------

    @Test
    void rejectsAWindowLongerThanTheMaximum() {
        assertThatThrownBy(() -> service.simulate(orgId, datasourceId,
                new SimulationWindow(FROM, FROM.plus(Duration.ofDays(200))), draft()))
                .isInstanceOf(InvalidSimulationPeriodException.class);
    }

    @Test
    void onlyExecutedSelectsAreReplayed() {
        service.simulate(orgId, datasourceId, window(), draft());

        var captor = org.mockito.ArgumentCaptor.forClass(QueryListFilter.class);
        verify(queryRequestLookupService).streamCorpusForOrganization(captor.capture(), anyInt(),
                any());
        assertThat(captor.getValue().status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(captor.getValue().queryType()).isEqualTo(QueryType.SELECT);
    }

    // ---- the diff --------------------------------------------------------------------------------

    @Test
    void aDraftThatMasksAReturnedColumnIsReportedAsNewlyMasked() {
        corpus.add(row(alice, "a@x.io"));
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("customers.email")));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.evaluatedCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.newlyMaskedCount()).isEqualTo(1);
        assertThat(result.newlyRevealedCount()).isZero();
        assertThat(result.columnImpacts()).singleElement().satisfies(column -> {
            assertThat(column.columnName()).isEqualTo("email");
            assertThat(column.newlyMaskedQueryCount()).isEqualTo(1);
        });
        assertThat(result.userImpacts()).singleElement().satisfies(user -> {
            assertThat(user.newlyMaskedColumns()).containsExactly("email");
            assertThat(user.newlyRevealedColumns()).isEmpty();
            assertThat(user.affectedQueryCount()).isEqualTo(1);
        });
    }

    @Test
    void removingAMaskIsReportedAsNewlyRevealed() {
        corpus.add(row(alice, "a@x.io"));
        when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of(mask("customers.email")));
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of());

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.newlyRevealedCount()).isEqualTo(1);
        assertThat(result.newlyMaskedCount()).isZero();
        assertThat(result.userImpacts().get(0).newlyRevealedColumns()).containsExactly("email");
    }

    @Test
    void aMaskOnAColumnTheQueryNeverReturnedChangesNothing() {
        corpus.add(row(alice, "a@x.io"));
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("customers.ssn")));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.evaluatedCount()).isEqualTo(1);
        assertThat(result.changedCount()).isZero();
        assertThat(result.samples()).isEmpty();
    }

    @Test
    void matchingIsOnTheBareColumnNameBecauseThatIsAllTheResultRecords() {
        corpus.add(row(alice, "a@x.io"));
        // A policy written for another table still matches the bare name — the persisted result
        // carries no schema or table, and over-masking is the safe direction.
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("orders.email")));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.newlyMaskedCount()).isEqualTo(1);
        assertThat(result.caveats()).contains(SimulationCaveat.COLUMN_MATCH_BARE_NAME);
    }

    // ---- rows with no stored result ---------------------------------------------------------------

    @Test
    void aQueryWithNoStoredResultIsSkippedRatherThanCountedAsUnchanged() {
        corpus.add(row(alice, "a@x.io"));
        when(queryResultPersistenceService.find(any())).thenReturn(Optional.empty());
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("customers.email")));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        // evaluatedCount is the honest denominator: rows nothing could be said about are not in it.
        assertThat(result.evaluatedCount()).isZero();
        assertThat(result.changedCount()).isZero();
    }

    @Test
    void unparseableStoredColumnsAreSkippedWithoutAbortingTheRun() {
        corpus.add(row(alice, "a@x.io"));
        corpus.add(row(bob, "b@x.io"));
        when(queryResultPersistenceService.find(any()))
                .thenReturn(Optional.of(new QueryResultPersistenceService.QueryResultSnapshot(
                        UUID.randomUUID(), "{not json", "[]", 1, false, null, 5)))
                .thenReturn(Optional.of(new QueryResultPersistenceService.QueryResultSnapshot(
                        UUID.randomUUID(), "[{\"name\":\"email\",\"jdbcType\":12}]", "[]", 1, false,
                        null, 5)));
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("customers.email")));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.evaluatedCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
    }

    // ---- aggregation ------------------------------------------------------------------------------

    @Test
    void maskResolutionIsMemoisedPerSubmitter() {
        corpus.add(row(alice, "a@x.io"));
        corpus.add(row(alice, "a@x.io"));
        corpus.add(row(bob, "b@x.io"));
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("customers.email")));

        service.simulate(orgId, datasourceId, window(), draft());

        verify(maskingPolicyResolutionService, times(2)).resolveApplicable(any(), any(), any());
        verify(maskingPolicyResolutionService, times(2))
                .resolveWithDraft(any(), any(), any(), any());
    }

    @Test
    void samplesAreCappedButCountsAreNot() {
        for (int i = 0; i < 5; i++) {
            corpus.add(row(alice, "a@x.io"));
        }
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("customers.email")));

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.changedCount()).isEqualTo(5);
        assertThat(result.samples()).hasSize(2);
    }

    @Test
    void moreRowsThanTheCapMarksTheResultTruncated() {
        for (int i = 0; i < 12; i++) {
            corpus.add(row(alice, "a@x.io"));
        }
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of());

        var result = service.simulate(orgId, datasourceId, window(), draft());

        assertThat(result.truncated()).isTrue();
        assertThat(result.evaluatedCount()).isEqualTo(10);
    }

    @Test
    void alwaysReportsTheMembershipAndBareNameCaveats() {
        assertThat(service.simulate(orgId, datasourceId, window(), draft()).caveats())
                .containsExactly(SimulationCaveat.MEMBERSHIP_STATE_CURRENT,
                        SimulationCaveat.COLUMN_MATCH_BARE_NAME);
    }

    @Test
    void samplesCarryNoCellValues() {
        corpus.add(row(alice, "a@x.io"));
        when(maskingPolicyResolutionService.resolveWithDraft(any(), any(), any(), any()))
                .thenReturn(List.of(mask("customers.email")));

        var sample = service.simulate(orgId, datasourceId, window(), draft()).samples().get(0);

        assertThat(sample.newlyMaskedColumns()).containsExactly("email");
        assertThat(sample.submittedByEmail()).isEqualTo("a@x.io");
        assertThat(sample.queryRequestId()).isNotNull();
    }
}
