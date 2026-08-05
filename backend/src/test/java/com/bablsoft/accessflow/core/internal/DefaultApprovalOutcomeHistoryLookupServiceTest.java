package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApprovalOutcomeHistoryLookupServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final Instant SINCE = Instant.parse("2026-07-01T00:00:00Z");

    @Mock QueryRequestRepository queryRequestRepository;

    DefaultApprovalOutcomeHistoryLookupService service;

    @BeforeEach
    void setUp() {
        service = new DefaultApprovalOutcomeHistoryLookupService(queryRequestRepository,
                new ObjectMapper());
    }

    // Row layout mirrors QueryRequestRepository.findApprovalOutcomeSampleRows:
    // 0 id, 1 queryType, 2 transactional, 3 createdAt, 4 submitterId, 5 datasourceId, 6 status,
    // 7 ai.riskScore, 8 ai.riskLevel, 9 ai.issues, 10 ai.failed,
    // 11 est.estimatedRows, 12 est.affectedRowCount, 13 est.estimatedCost, 14 est.scanType,
    // 15 est.supported, 16 est.failed
    private static Object[] row(QueryStatus status, Integer riskScore, RiskLevel riskLevel,
                                String issues, Boolean aiFailed, Long estimatedRows,
                                Long affectedRowCount, Double estimatedCost, String scanType,
                                Boolean supported, Boolean estFailed) {
        return new Object[] {UUID.randomUUID(), QueryType.SELECT, Boolean.FALSE,
                Instant.parse("2026-07-15T12:00:00Z"), UUID.randomUUID(), UUID.randomUUID(),
                status, riskScore, riskLevel, issues, aiFailed, estimatedRows, affectedRowCount,
                estimatedCost, scanType, supported, estFailed};
    }

    private static Object[] rowWithoutJoins(QueryStatus status) {
        return row(status, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void mapsFullRowIncludingIssueCount() {
        var full = row(QueryStatus.EXECUTED, 42, RiskLevel.HIGH,
                "[{\"code\":\"A\"},{\"code\":\"B\"}]", false, 100L, 50L, 12.5, "Seq Scan",
                true, false);
        when(queryRequestRepository.findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), any()))
                .thenReturn(List.<Object[]>of(full));

        var samples = service.findDecidedSamples(ORG_ID, SINCE, 50);

        assertThat(samples).hasSize(1);
        var sample = samples.get(0);
        assertThat(sample.queryRequestId()).isEqualTo(full[0]);
        assertThat(sample.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(sample.transactional()).isFalse();
        assertThat(sample.createdAt()).isEqualTo(Instant.parse("2026-07-15T12:00:00Z"));
        assertThat(sample.submitterId()).isEqualTo(full[4]);
        assertThat(sample.datasourceId()).isEqualTo(full[5]);
        assertThat(sample.aiRiskScore()).isEqualTo(42);
        assertThat(sample.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(sample.aiIssueCount()).isEqualTo(2);
        assertThat(sample.aiMissing()).isFalse();
        assertThat(sample.estimatedRows()).isEqualTo(100L);
        assertThat(sample.affectedRowCount()).isEqualTo(50L);
        assertThat(sample.estimatedCost()).isEqualTo(12.5);
        assertThat(sample.scanType()).isEqualTo("Seq Scan");
        assertThat(sample.estimateMissing()).isFalse();
        assertThat(sample.approved()).isTrue();
    }

    @Test
    void flagsAiMissingWhenNoAnalysisRow() {
        when(queryRequestRepository.findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), any()))
                .thenReturn(List.<Object[]>of(rowWithoutJoins(QueryStatus.REJECTED)));

        var sample = service.findDecidedSamples(ORG_ID, SINCE, 50).get(0);

        assertThat(sample.aiMissing()).isTrue();
        assertThat(sample.aiRiskScore()).isNull();
        assertThat(sample.aiRiskLevel()).isNull();
        assertThat(sample.aiIssueCount()).isNull();
    }

    @Test
    void flagsAiMissingWhenAnalysisFailed() {
        var failedAi = row(QueryStatus.EXECUTED, 0, RiskLevel.LOW, "[]", true, 10L, null, 1.0,
                "Seq Scan", true, false);
        when(queryRequestRepository.findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), any()))
                .thenReturn(List.<Object[]>of(failedAi));

        var sample = service.findDecidedSamples(ORG_ID, SINCE, 50).get(0);

        assertThat(sample.aiMissing()).isTrue();
        assertThat(sample.aiRiskScore()).isNull();
        assertThat(sample.aiRiskLevel()).isNull();
        assertThat(sample.aiIssueCount()).isNull();
        assertThat(sample.estimateMissing()).isFalse();
    }

    @Test
    void flagsEstimateMissingWhenAbsentUnsupportedOrFailed() {
        var absent = rowWithoutJoins(QueryStatus.TIMED_OUT);
        var unsupported = row(QueryStatus.EXECUTED, 5, RiskLevel.LOW, "[]", false, null, null,
                null, null, false, false);
        var failed = row(QueryStatus.EXECUTED, 5, RiskLevel.LOW, "[]", false, null, null, null,
                null, true, true);
        when(queryRequestRepository.findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), any()))
                .thenReturn(List.of(absent, unsupported, failed));

        var samples = service.findDecidedSamples(ORG_ID, SINCE, 50);

        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.estimateMissing()).isTrue();
            assertThat(sample.estimatedRows()).isNull();
            assertThat(sample.affectedRowCount()).isNull();
            assertThat(sample.estimatedCost()).isNull();
            assertThat(sample.scanType()).isNull();
        });
    }

    @Test
    void derivesApprovedLabelFromTerminalStatus() {
        when(queryRequestRepository.findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), any()))
                .thenReturn(List.of(rowWithoutJoins(QueryStatus.APPROVED),
                        rowWithoutJoins(QueryStatus.EXECUTED),
                        rowWithoutJoins(QueryStatus.REJECTED),
                        rowWithoutJoins(QueryStatus.TIMED_OUT)));

        var samples = service.findDecidedSamples(ORG_ID, SINCE, 50);

        assertThat(samples).extracting("approved")
                .containsExactly(true, true, false, false);
    }

    @Test
    void countsUnparseableIssuesJsonAsZero() {
        var garbage = row(QueryStatus.EXECUTED, 7, RiskLevel.MEDIUM, "not json", false, null,
                null, null, null, null, null);
        when(queryRequestRepository.findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), any()))
                .thenReturn(List.<Object[]>of(garbage));

        var sample = service.findDecidedSamples(ORG_ID, SINCE, 50).get(0);

        assertThat(sample.aiMissing()).isFalse();
        assertThat(sample.aiIssueCount()).isZero();
    }

    @Test
    void limitsToMaxRowsViaPageable() {
        when(queryRequestRepository.findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), any()))
                .thenReturn(List.of());

        service.findDecidedSamples(ORG_ID, SINCE, 7);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(queryRequestRepository)
                .findApprovalOutcomeSampleRows(eq(ORG_ID), eq(SINCE), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    void returnsEmptyForNonPositiveMaxRowsWithoutQuerying() {
        assertThat(service.findDecidedSamples(ORG_ID, SINCE, 0)).isEmpty();
        assertThat(service.findDecidedSamples(ORG_ID, SINCE, -1)).isEmpty();
        verifyNoInteractions(queryRequestRepository);
    }

    @Test
    void submitterCountsMapsAggregateRow() {
        var userId = UUID.randomUUID();
        when(queryRequestRepository.countApprovalOutcomesBySubmitter(ORG_ID, userId, SINCE))
                .thenReturn(List.<Object[]>of(new Object[] {5L, 3L}));

        var counts = service.submitterCounts(ORG_ID, userId, SINCE);

        assertThat(counts.decided()).isEqualTo(5);
        assertThat(counts.approved()).isEqualTo(3);
    }

    @Test
    void datasourceCountsMapsAggregateRow() {
        var datasourceId = UUID.randomUUID();
        when(queryRequestRepository.countApprovalOutcomesByDatasource(ORG_ID, datasourceId, SINCE))
                .thenReturn(List.<Object[]>of(new Object[] {8L, 2L}));

        var counts = service.datasourceCounts(ORG_ID, datasourceId, SINCE);

        assertThat(counts.decided()).isEqualTo(8);
        assertThat(counts.approved()).isEqualTo(2);
    }

    @Test
    void countsFallBackToZeroOnEmptyAggregate() {
        var userId = UUID.randomUUID();
        when(queryRequestRepository.countApprovalOutcomesBySubmitter(ORG_ID, userId, SINCE))
                .thenReturn(List.of());

        var counts = service.submitterCounts(ORG_ID, userId, SINCE);

        assertThat(counts.decided()).isZero();
        assertThat(counts.approved()).isZero();
    }
}
