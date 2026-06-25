package com.bablsoft.accessflow.notifications.internal;

import java.time.LocalDate;

/**
 * The payload of a weekly-digest notification (AF-498), carried on {@link NotificationContext} for the
 * {@code WEEKLY_DIGEST} event. Only populated for that event; null otherwise.
 */
public record WeeklyDigestData(
        LocalDate weekStart,
        LocalDate weekEnd,
        long totalQueries,
        long pendingApprovals,
        long openAnomalies,
        long openSuggestions) {
}
