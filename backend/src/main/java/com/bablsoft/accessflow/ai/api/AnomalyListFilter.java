package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter for {@link BehaviorAnomalyAdminService#list}. All fields optional; null means "no filter on
 * this field". {@code from}/{@code to} bound {@code detected_at}.
 */
public record AnomalyListFilter(
        UUID userId,
        UUID datasourceId,
        String feature,
        BehaviorAnomalyStatus status,
        Instant from,
        Instant to) {

    public static AnomalyListFilter empty() {
        return new AnomalyListFilter(null, null, null, null, null, null);
    }
}
