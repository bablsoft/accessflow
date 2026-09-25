package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.ByteSizeFormat;
import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;

import java.util.Locale;
import java.util.UUID;

/**
 * The budget-specific part of a {@code DATA_BUDGET_*} notification (#942). A limit that is not set
 * on the budget ({@code maxRows} / {@code maxBytes} null) renders no usage line at all.
 */
public record DataBudgetNotice(
        UUID budgetId,
        String budgetName,
        boolean exhausted,
        Integer warnThresholdPercent,
        int usedPercent,
        Long maxRows,
        Long maxBytes,
        long usedRows,
        long usedBytes,
        int windowMinutes,
        DataBudgetBreachAction breachAction) {

    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    /** {@code "1200 / 5000 rows"}, or null when the budget caps no rows. */
    public String rowsUsage() {
        return maxRows == null ? null : usedRows + " / " + maxRows + " rows";
    }

    /** {@code "1.2 GB (…) / 5 GB (…)"}, or null when the budget caps no bytes. */
    public String bytesUsage() {
        return maxBytes == null ? null
                : ByteSizeFormat.format(usedBytes) + " / " + ByteSizeFormat.format(maxBytes);
    }

    /** The largest whole unit the window divides into: {@code DAYS}, {@code HOURS} or {@code MINUTES}. */
    public String windowUnit() {
        if (windowMinutes > 0 && windowMinutes % MINUTES_PER_DAY == 0) {
            return "DAYS";
        }
        if (windowMinutes > 0 && windowMinutes % MINUTES_PER_HOUR == 0) {
            return "HOURS";
        }
        return "MINUTES";
    }

    public int windowValue() {
        return switch (windowUnit()) {
            case "DAYS" -> windowMinutes / MINUTES_PER_DAY;
            case "HOURS" -> windowMinutes / MINUTES_PER_HOUR;
            default -> windowMinutes;
        };
    }

    /** English window label for the chat channels, e.g. {@code "7 days"} or {@code "1 hour"}. */
    public String windowLabel() {
        var value = windowValue();
        var unit = windowUnit().toLowerCase(Locale.ROOT);
        return value + " " + (value == 1 ? unit.substring(0, unit.length() - 1) : unit);
    }
}
