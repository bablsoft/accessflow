package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisStatsLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsRaw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAnalysisStatsServiceTest {

    @Mock AiAnalysisStatsLookupService lookupService;

    @Test
    void usesSuppliedRangeAndDatasourceWhenProvided() {
        var service = new AiAnalysisStatsService(lookupService);
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var from = Instant.parse("2026-04-01T00:00:00Z");
        var to = Instant.parse("2026-05-01T00:00:00Z");
        var raw = new AiAnalysisStatsRaw(List.of(), List.of(), List.of());
        when(lookupService.query(orgId, from, to, datasourceId)).thenReturn(raw);

        var result = service.stats(orgId, from, to, datasourceId);

        assertThat(result).isSameAs(raw);
        verify(lookupService).query(orgId, from, to, datasourceId);
    }

    @Test
    void defaultsToLast30DaysWhenFromAndToAreNull() {
        var service = new AiAnalysisStatsService(lookupService);
        var orgId = UUID.randomUUID();
        when(lookupService.query(eq(orgId), any(), any(), eq(null)))
                .thenReturn(new AiAnalysisStatsRaw(List.of(), List.of(), List.of()));

        var before = Instant.now();
        service.stats(orgId, null, null, null);
        var after = Instant.now();

        var fromCaptor = ArgumentCaptor.forClass(Instant.class);
        var toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(lookupService).query(eq(orgId), fromCaptor.capture(), toCaptor.capture(), eq(null));
        Instant captTo = toCaptor.getValue();
        Instant captFrom = fromCaptor.getValue();
        assertThat(captTo).isBetween(before, after.plusMillis(1));
        assertThat(Duration.between(captFrom, captTo)).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void defaultsFromToToMinus30DaysWhenOnlyToProvided() {
        var service = new AiAnalysisStatsService(lookupService);
        var orgId = UUID.randomUUID();
        var to = Instant.parse("2026-05-15T00:00:00Z");
        when(lookupService.query(eq(orgId), any(), eq(to), eq(null)))
                .thenReturn(new AiAnalysisStatsRaw(List.of(), List.of(), List.of()));

        service.stats(orgId, null, to, null);

        var fromCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(lookupService).query(eq(orgId), fromCaptor.capture(), eq(to), eq(null));
        assertThat(fromCaptor.getValue()).isEqualTo(to.minus(Duration.ofDays(30)));
    }

    @Test
    void defaultsToToNowWhenOnlyFromProvided() {
        var service = new AiAnalysisStatsService(lookupService);
        var orgId = UUID.randomUUID();
        var from = Instant.parse("2026-04-01T00:00:00Z");
        when(lookupService.query(eq(orgId), eq(from), any(), eq(null)))
                .thenReturn(new AiAnalysisStatsRaw(List.of(), List.of(), List.of()));

        var before = Instant.now();
        service.stats(orgId, from, null, null);
        var after = Instant.now();

        var toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(lookupService).query(eq(orgId), eq(from), toCaptor.capture(), eq(null));
        assertThat(toCaptor.getValue()).isBetween(before, after.plusMillis(1));
    }

    @Test
    void throwsWhenFromIsAfterTo() {
        var service = new AiAnalysisStatsService(lookupService);
        var orgId = UUID.randomUUID();
        var from = Instant.parse("2026-05-15T00:00:00Z");
        var to = Instant.parse("2026-05-01T00:00:00Z");

        assertThatThrownBy(() -> service.stats(orgId, from, to, null))
                .isInstanceOf(BadAiAnalysisStatsQueryException.class)
                .hasMessage("error.ai_analysis_stats.invalid_range");
    }
}
