package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyResponseTest {

    @Test
    void fromMapsEveryFieldIncludingDetail() {
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var acknowledgedBy = UUID.randomUUID();
        var detectedAt = Instant.parse("2026-01-01T10:00:00Z");
        var acknowledgedAt = Instant.parse("2026-01-01T11:00:00Z");
        var windowStart = Instant.parse("2026-01-01T09:00:00Z");
        var windowEnd = Instant.parse("2026-01-01T10:00:00Z");
        var detail = Map.<String, Object>of("method", "zscore", "z", 4.2);

        var view = new BehaviorAnomalyView(id, orgId, userId, "Alice", "alice@example.com",
                datasourceId, "Prod DB", "query_count", 4.2, 100.0, 10.0, 2.0, detail,
                "ai summary text", BehaviorAnomalyStatus.ACKNOWLEDGED, detectedAt,
                acknowledgedBy, acknowledgedAt, windowStart, windowEnd);

        var response = AnomalyResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.userDisplayName()).isEqualTo("Alice");
        assertThat(response.userEmail()).isEqualTo("alice@example.com");
        assertThat(response.datasourceId()).isEqualTo(datasourceId);
        assertThat(response.datasourceName()).isEqualTo("Prod DB");
        assertThat(response.feature()).isEqualTo("query_count");
        assertThat(response.score()).isEqualTo(4.2);
        assertThat(response.observedValue()).isEqualTo(100.0);
        assertThat(response.baselineMean()).isEqualTo(10.0);
        assertThat(response.baselineStddev()).isEqualTo(2.0);
        assertThat(response.detail()).containsEntry("method", "zscore").containsEntry("z", 4.2);
        assertThat(response.aiSummary()).isEqualTo("ai summary text");
        assertThat(response.status()).isEqualTo(BehaviorAnomalyStatus.ACKNOWLEDGED);
        assertThat(response.detectedAt()).isEqualTo(detectedAt);
        assertThat(response.acknowledgedBy()).isEqualTo(acknowledgedBy);
        assertThat(response.acknowledgedAt()).isEqualTo(acknowledgedAt);
        assertThat(response.windowStart()).isEqualTo(windowStart);
        assertThat(response.windowEnd()).isEqualTo(windowEnd);
    }

    @Test
    void fromHandlesNullEnrichmentsAndScalarMagnitudes() {
        var view = new BehaviorAnomalyView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, UUID.randomUUID(), null, "active_hours", 99.0, null, null, null,
                Map.of(), null, BehaviorAnomalyStatus.OPEN, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T01:00:00Z"));

        var response = AnomalyResponse.from(view);

        assertThat(response.userDisplayName()).isNull();
        assertThat(response.userEmail()).isNull();
        assertThat(response.datasourceName()).isNull();
        assertThat(response.observedValue()).isNull();
        assertThat(response.baselineMean()).isNull();
        assertThat(response.baselineStddev()).isNull();
        assertThat(response.aiSummary()).isNull();
        assertThat(response.acknowledgedBy()).isNull();
        assertThat(response.acknowledgedAt()).isNull();
        assertThat(response.detail()).isEmpty();
    }
}
