package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.core.api.PageResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyPageResponseTest {

    private static AnomalyResponse anomalyResponse(String feature) {
        return new AnomalyResponse(UUID.randomUUID(), UUID.randomUUID(), "Alice",
                "alice@example.com", UUID.randomUUID(), "Prod DB", feature, 4.2, 100.0, 10.0, 2.0,
                Map.of("method", "zscore"), null, BehaviorAnomalyStatus.OPEN,
                Instant.parse("2026-01-01T00:00:00Z"), null, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));
    }

    @Test
    void fromMapsContentAndPaginationMetadata() {
        var page = new PageResponse<>(List.of(anomalyResponse("query_count")), 2, 20, 41L, 3);

        var response = AnomalyPageResponse.from(page);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).feature()).isEqualTo("query_count");
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(41L);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void fromMapsEmptyPage() {
        var response = AnomalyPageResponse.from(PageResponse.empty(0, 20));
        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }
}
