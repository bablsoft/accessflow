package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.internal.persistence.repo.MyQueryInsightsRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMyQueryInsightsLookupServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");

    private final MyQueryInsightsRepository repo = mock(MyQueryInsightsRepository.class);
    private final DefaultMyQueryInsightsLookupService service =
            new DefaultMyQueryInsightsLookupService(repo);

    private MyQueryInsightsRepository.StatusBucketRow statusRow(LocalDate d, String status, long n) {
        var r = mock(MyQueryInsightsRepository.StatusBucketRow.class);
        when(r.getBucketDate()).thenReturn(d);
        when(r.getStatus()).thenReturn(status);
        when(r.getCnt()).thenReturn(n);
        return r;
    }

    private MyQueryInsightsRepository.RiskBucketRow riskRow(LocalDate d, String risk, long n) {
        var r = mock(MyQueryInsightsRepository.RiskBucketRow.class);
        when(r.getBucketDate()).thenReturn(d);
        when(r.getRiskLevel()).thenReturn(risk);
        when(r.getCnt()).thenReturn(n);
        return r;
    }

    @Test
    void trendsMapEnumStringsToEnums() {
        var d = LocalDate.of(2026, 6, 10);
        var statusRow = statusRow(d, "EXECUTED", 3);
        var riskRow = riskRow(d, "HIGH", 2);
        when(repo.findStatusByDay(ORG, USER, FROM, TO)).thenReturn(List.of(statusRow));
        when(repo.findRiskByDay(ORG, USER, FROM, TO)).thenReturn(List.of(riskRow));

        var trends = service.trends(ORG, USER, FROM, TO);

        assertThat(trends.statusByDay()).singleElement().satisfies(b -> {
            assertThat(b.status()).isEqualTo(QueryStatus.EXECUTED);
            assertThat(b.count()).isEqualTo(3);
            assertThat(b.date()).isEqualTo(d);
        });
        assertThat(trends.riskByDay()).singleElement()
                .satisfies(b -> assertThat(b.riskLevel()).isEqualTo(RiskLevel.HIGH));
    }

    @Test
    void statusCountsMapRows() {
        var r = mock(MyQueryInsightsRepository.StatusCountRow.class);
        when(r.getStatus()).thenReturn("PENDING_REVIEW");
        when(r.getCnt()).thenReturn(4L);
        when(repo.findStatusCounts(ORG, USER)).thenReturn(List.of(r));

        var counts = service.statusCounts(ORG, USER);

        assertThat(counts).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
            assertThat(c.count()).isEqualTo(4);
        });
    }

    @Test
    void recentOptimizationSourcesMapRows() {
        var r = mock(MyQueryInsightsRepository.OptimizationSourceRow.class);
        var analysisId = UUID.randomUUID();
        when(r.getAiAnalysisId()).thenReturn(analysisId);
        when(r.getQueryRequestId()).thenReturn(UUID.randomUUID());
        when(r.getDatasourceId()).thenReturn(UUID.randomUUID());
        when(r.getDatasourceName()).thenReturn("DB");
        when(r.getDbType()).thenReturn("POSTGRESQL");
        when(r.getRiskLevel()).thenReturn("LOW");
        when(r.getOptimizations()).thenReturn("[]");
        when(r.getCreatedAt()).thenReturn(FROM);
        when(repo.findRecentOptimizationSources(ORG, USER, 50)).thenReturn(List.of(r));

        var sources = service.recentOptimizationSources(ORG, USER, 50);

        assertThat(sources).singleElement().satisfies(s -> {
            assertThat(s.aiAnalysisId()).isEqualTo(analysisId);
            assertThat(s.dbType()).isEqualTo(DbType.POSTGRESQL);
            assertThat(s.riskLevel()).isEqualTo(RiskLevel.LOW);
        });
    }

    @Test
    void recentOptimizationSourcesShortCircuitsOnNonPositiveLimit() {
        assertThat(service.recentOptimizationSources(ORG, USER, 0)).isEmpty();
        verify(repo, never()).findRecentOptimizationSources(any(), any(), anyInt());
    }

    @Test
    void rejectsNullScope() {
        assertThatThrownBy(() -> service.statusCounts(null, USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.trends(ORG, null, FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
