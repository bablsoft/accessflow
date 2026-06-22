package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter for {@link BreakGlassAdminService#list}. All fields optional; null means "no filter on
 * this field". {@code from}/{@code to} bound {@code created_at}.
 */
public record BreakGlassEventFilter(
        BreakGlassStatus status,
        UUID datasourceId,
        UUID submittedByUserId,
        Instant from,
        Instant to) {

    public static BreakGlassEventFilter empty() {
        return new BreakGlassEventFilter(null, null, null, null, null);
    }
}
