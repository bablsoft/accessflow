package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiAnalysisStatsLookupServiceTest {

    @Mock AiAnalysisStatsRepository repository;
    @InjectMocks DefaultAiAnalysisStatsLookupService service;

    @Test
    void queryReturnsEmptyListsWhenRepositoryReturnsNothing() {
        var orgId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");
        when(repository.findRiskScoreByDay(orgId, from, to, null)).thenReturn(List.of());
        when(repository.findTopIssueCategories(orgId, from, to, null)).thenReturn(List.of());
        when(repository.findTopSubmitters(orgId, from, to, null)).thenReturn(List.of());

        var result = service.query(orgId, from, to, null);

        assertThat(result.riskScoreOverTime()).isEmpty();
        assertThat(result.topIssueCategories()).isEmpty();
        assertThat(result.topSubmitters()).isEmpty();
    }

    @Test
    void queryMapsAllProjectionsToApiViews() {
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");
        when(repository.findRiskScoreByDay(orgId, from, to, datasourceId))
                .thenReturn(List.of(bucket(LocalDate.of(2026, 1, 15), new BigDecimal("47.5"), 4L, 3L)));
        when(repository.findTopIssueCategories(orgId, from, to, datasourceId))
                .thenReturn(List.of(category("performance", 12L), category("security", 5L)));
        when(repository.findTopSubmitters(orgId, from, to, datasourceId))
                .thenReturn(List.of(submitter(userId, "alice@x.test", "Alice", 9L)));

        var result = service.query(orgId, from, to, datasourceId);

        assertThat(result.riskScoreOverTime())
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.date()).isEqualTo(LocalDate.of(2026, 1, 15));
                    assertThat(p.successAvgRiskScore()).isEqualByComparingTo(new BigDecimal("47.5"));
                    assertThat(p.totalCount()).isEqualTo(4L);
                    assertThat(p.successCount()).isEqualTo(3L);
                });
        assertThat(result.topIssueCategories())
                .extracting("category", "count")
                .containsExactly(tuple("performance", 12L), tuple("security", 5L));
        assertThat(result.topSubmitters())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.userId()).isEqualTo(userId);
                    assertThat(s.email()).isEqualTo("alice@x.test");
                    assertThat(s.displayName()).isEqualTo("Alice");
                    assertThat(s.count()).isEqualTo(9L);
                });
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static AiAnalysisStatsRepository.RiskScoreBucketRow bucket(
            LocalDate date, BigDecimal avg, long total, long success) {
        return new AiAnalysisStatsRepository.RiskScoreBucketRow() {
            @Override public LocalDate getBucketDate() { return date; }
            @Override public BigDecimal getSuccessAvgRiskScore() { return avg; }
            @Override public long getTotalCount() { return total; }
            @Override public long getSuccessCount() { return success; }
        };
    }

    private static AiAnalysisStatsRepository.IssueCategoryRow category(String category, long count) {
        return new AiAnalysisStatsRepository.IssueCategoryRow() {
            @Override public String getCategory() { return category; }
            @Override public long getCnt() { return count; }
        };
    }

    private static AiAnalysisStatsRepository.SubmitterRow submitter(
            UUID userId, String email, String displayName, long count) {
        return new AiAnalysisStatsRepository.SubmitterRow() {
            @Override public UUID getUserId() { return userId; }
            @Override public String getEmail() { return email; }
            @Override public String getDisplayName() { return displayName; }
            @Override public long getCnt() { return count; }
        };
    }
}
