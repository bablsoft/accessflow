package com.bablsoft.accessflow.dashboard.events;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by {@code WeeklyDigestJob} when a user opted into the weekly digest is due (AF-498). The
 * {@code notifications} module consumes it and fans the summary out over the user's channels (email +
 * chat). Self-contained JDK-typed payload so the consumer renders it without calling back into the
 * dashboard module (keeps the dependency one-way: {@code notifications → dashboard}).
 */
public record WeeklyDigestReadyEvent(
        UUID organizationId,
        UUID userId,
        LocalDate weekStart,
        LocalDate weekEnd,
        long totalQueries,
        long pendingApprovals,
        long openAnomalies,
        long openSuggestions) {
}
