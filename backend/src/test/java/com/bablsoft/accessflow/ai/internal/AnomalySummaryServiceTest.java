package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.AnomalyDetectionProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AnomalySummaryServiceTest {

    private final AiAnalyzerStrategyHolder holder = mock(AiAnalyzerStrategyHolder.class);
    private final ObjectProvider<AiAnalyzerStrategyHolder> holderProvider = mock(ObjectProvider.class);

    AnomalySummaryServiceTest() {
        when(holderProvider.getObject()).thenReturn(holder);
    }

    private AnomalyDetectionProperties props(boolean summaryEnabled) {
        return new AnomalyDetectionProperties(Duration.ofHours(1), 3, 1.5, 7, 90, 0.02, summaryEnabled);
    }

    private static DetectedAnomaly anomaly() {
        return new DetectedAnomaly("query_count", 4.2, 100.0, 10.0, 2.0,
                Map.of("method", "zscore", "z", 4.2));
    }

    @Test
    void returnsEmptyWithoutCallingHolderWhenSummaryDisabled() {
        var service = new AnomalySummaryService(holderProvider, props(false));

        var result = service.summarize(UUID.randomUUID(), anomaly(), "Alice", "Prod DB");

        assertThat(result).isEmpty();
        verifyNoInteractions(holder);
    }

    @Test
    void delegatesToHolderWhenEnabledAndReturnsItsValue() {
        var service = new AnomalySummaryService(holderProvider, props(true));
        var orgId = UUID.randomUUID();
        when(holder.summarizeFreeform(eq(orgId), anyString())).thenReturn(Optional.of("explanation"));

        var result = service.summarize(orgId, anomaly(), "Alice", "Prod DB");

        assertThat(result).contains("explanation");
        verify(holder).summarizeFreeform(eq(orgId), anyString());
    }

    @Test
    void propagatesEmptyFromHolder() {
        var service = new AnomalySummaryService(holderProvider, props(true));
        when(holder.summarizeFreeform(any(), anyString())).thenReturn(Optional.empty());

        var result = service.summarize(UUID.randomUUID(), anomaly(), "Alice", "Prod DB");

        assertThat(result).isEmpty();
    }

    @Test
    void buildsPromptWithUserDatasourceFeatureAndScalarContext() {
        var service = new AnomalySummaryService(holderProvider, props(true));
        when(holder.summarizeFreeform(any(), anyString())).thenReturn(Optional.of("ok"));

        service.summarize(UUID.randomUUID(), anomaly(), "Alice", "Prod DB");

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(holder).summarizeFreeform(any(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("User: Alice")
                .contains("Datasource: Prod DB")
                .contains("Anomalous feature: query_count")
                .contains("Observed value: 100.0")
                .contains("Baseline mean: 10.0")
                .contains("stddev 2.0")
                .contains("Deviation score: 4.2")
                .contains("Detection context:");
    }

    @Test
    void promptOmitsScalarLinesForCategoricalAnomaly() {
        var service = new AnomalySummaryService(holderProvider, props(true));
        when(holder.summarizeFreeform(any(), anyString())).thenReturn(Optional.of("ok"));
        var categorical = new DetectedAnomaly("active_hours", 99.0, null, null, null,
                Map.of("method", "off_hours", "hour", 3));

        service.summarize(UUID.randomUUID(), categorical, "Bob", "Reporting");

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(holder).summarizeFreeform(any(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("Anomalous feature: active_hours")
                .doesNotContain("Observed value:")
                .doesNotContain("Baseline mean:");
    }

    @Test
    void promptIncludesMeanWithoutStddevWhenStddevNull() {
        var service = new AnomalySummaryService(holderProvider, props(true));
        when(holder.summarizeFreeform(any(), anyString())).thenReturn(Optional.of("ok"));
        var noStddev = new DetectedAnomaly("rows_returned", 5.0, 500.0, 50.0, null,
                Map.of("method", "zscore"));

        service.summarize(UUID.randomUUID(), noStddev, "Carol", "DW");

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(holder).summarizeFreeform(any(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("Baseline mean: 50.0")
                .doesNotContain("stddev");
    }
}
