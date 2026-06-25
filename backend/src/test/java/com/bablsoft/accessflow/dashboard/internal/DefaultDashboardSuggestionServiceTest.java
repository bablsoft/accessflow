package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MyOptimizationSourceView;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionStatus;
import com.bablsoft.accessflow.dashboard.api.InvalidSuggestionIdException;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardSuggestionStateEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardSuggestionStateRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDashboardSuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final MyQueryInsightsLookupService insights = mock(MyQueryInsightsLookupService.class);
    private final DashboardSuggestionStateRepository stateRepo =
            mock(DashboardSuggestionStateRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DefaultDashboardSuggestionService service =
            new DefaultDashboardSuggestionService(insights, stateRepo, objectMapper, clock);

    private MyOptimizationSourceView source(UUID analysisId, String optimizationsJson) {
        return new MyOptimizationSourceView(analysisId, UUID.randomUUID(), UUID.randomUUID(),
                "Prod DB", DbType.POSTGRESQL, RiskLevel.HIGH, optimizationsJson, NOW);
    }

    private static final String TWO_ITEMS = """
            [{"type":"INDEX","title":"Add index","rationale":"speed","sql":"CREATE INDEX i ON t(c)"},
             {"type":"REWRITE","title":"Rewrite","rationale":"avoid scan","sql":"SELECT 1"}]""";

    @Test
    void listOpenBuildsSuggestionsWithStableIds() {
        var analysisId = UUID.randomUUID();
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt()))
                .thenReturn(List.of(source(analysisId, TWO_ITEMS)));
        when(stateRepo.findByOrganizationIdAndUserIdAndAiAnalysisIdIn(eq(ORG), eq(USER), any()))
                .thenReturn(List.of());

        var result = service.listOpen(ORG, USER);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(analysisId + ":0");
        assertThat(result.get(0).title()).isEqualTo("Add index");
        assertThat(result.get(1).id()).isEqualTo(analysisId + ":1");
        assertThat(result.get(0).status()).isEqualTo(DashboardSuggestionStatus.OPEN);
    }

    @Test
    void listOpenFiltersOutDismissed() {
        var analysisId = UUID.randomUUID();
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt()))
                .thenReturn(List.of(source(analysisId, TWO_ITEMS)));
        var dismissed = new DashboardSuggestionStateEntity();
        dismissed.setAiAnalysisId(analysisId);
        dismissed.setSuggestionIndex(0);
        dismissed.setStatus(DashboardSuggestionStatus.DISMISSED);
        when(stateRepo.findByOrganizationIdAndUserIdAndAiAnalysisIdIn(eq(ORG), eq(USER), any()))
                .thenReturn(List.of(dismissed));

        var result = service.listOpen(ORG, USER);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).index()).isEqualTo(1);
    }

    @Test
    void listOpenReturnsEmptyWhenNoSources() {
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt())).thenReturn(List.of());
        assertThat(service.listOpen(ORG, USER)).isEmpty();
    }

    @Test
    void listOpenSkipsUnparseableJson() {
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt()))
                .thenReturn(List.of(source(UUID.randomUUID(), "{ broken")));
        when(stateRepo.findByOrganizationIdAndUserIdAndAiAnalysisIdIn(eq(ORG), eq(USER), any()))
                .thenReturn(List.of());
        assertThat(service.listOpen(ORG, USER)).isEmpty();
    }

    @Test
    void countOpenReturnsBacklogSize() {
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt()))
                .thenReturn(List.of(source(UUID.randomUUID(), TWO_ITEMS)));
        when(stateRepo.findByOrganizationIdAndUserIdAndAiAnalysisIdIn(eq(ORG), eq(USER), any()))
                .thenReturn(List.of());
        assertThat(service.countOpen(ORG, USER)).isEqualTo(2);
    }

    @Test
    void dismissPersistsNewDismissedState() {
        var analysisId = UUID.randomUUID();
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt()))
                .thenReturn(List.of(source(analysisId, TWO_ITEMS)));
        when(stateRepo.findByOrganizationIdAndUserIdAndAiAnalysisIdAndSuggestionIndex(
                ORG, USER, analysisId, 1)).thenReturn(Optional.empty());

        service.dismiss(ORG, USER, analysisId + ":1");

        verify(stateRepo).save(any(DashboardSuggestionStateEntity.class));
    }

    @Test
    void dismissUpdatesExistingState() {
        var analysisId = UUID.randomUUID();
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt()))
                .thenReturn(List.of(source(analysisId, TWO_ITEMS)));
        var existing = new DashboardSuggestionStateEntity();
        existing.setStatus(DashboardSuggestionStatus.APPLIED);
        when(stateRepo.findByOrganizationIdAndUserIdAndAiAnalysisIdAndSuggestionIndex(
                ORG, USER, analysisId, 0)).thenReturn(Optional.of(existing));

        service.dismiss(ORG, USER, analysisId + ":0");

        assertThat(existing.getStatus()).isEqualTo(DashboardSuggestionStatus.DISMISSED);
        verify(stateRepo).save(existing);
    }

    @Test
    void dismissRejectsMalformedId() {
        assertThatThrownBy(() -> service.dismiss(ORG, USER, "not-an-id"))
                .isInstanceOf(InvalidSuggestionIdException.class);
        assertThatThrownBy(() -> service.dismiss(ORG, USER, "uuid:"))
                .isInstanceOf(InvalidSuggestionIdException.class);
        assertThatThrownBy(() -> service.dismiss(ORG, USER, null))
                .isInstanceOf(InvalidSuggestionIdException.class);
    }

    @Test
    void dismissRejectsUnknownAnalysis() {
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt())).thenReturn(List.of());
        assertThatThrownBy(() -> service.dismiss(ORG, USER, UUID.randomUUID() + ":0"))
                .isInstanceOf(InvalidSuggestionIdException.class);
        verify(stateRepo, never()).save(any());
    }

    @Test
    void dismissRejectsOutOfRangeIndex() {
        var analysisId = UUID.randomUUID();
        when(insights.recentOptimizationSources(eq(ORG), eq(USER), anyInt()))
                .thenReturn(List.of(source(analysisId, TWO_ITEMS)));
        assertThatThrownBy(() -> service.dismiss(ORG, USER, analysisId + ":9"))
                .isInstanceOf(InvalidSuggestionIdException.class);
    }
}
