package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * One budget's consumption for one user over its trailing window (#942). A null limit means the
 * budget does not bound that metric; the matching {@code remaining*} is then null too.
 */
public record DataBudgetConsumption(
        UUID budgetId,
        String name,
        Long maxRows,
        Long maxBytes,
        int windowMinutes,
        DataBudgetBreachAction breachAction,
        Integer warnThresholdPercent,
        long usedRows,
        long usedBytes) {

    public Long remainingRows() {
        return maxRows == null ? null : Math.max(0, maxRows - usedRows);
    }

    public Long remainingBytes() {
        return maxBytes == null ? null : Math.max(0, maxBytes - usedBytes);
    }

    public boolean exhausted() {
        return (maxRows != null && usedRows >= maxRows) || (maxBytes != null && usedBytes >= maxBytes);
    }

    /** The larger of the rows and bytes percentages used, unclamped (may exceed 100). */
    public double usedPercent() {
        return Math.max(percent(usedRows, maxRows), percent(usedBytes, maxBytes));
    }

    /** This consumption after {@code rows} / {@code bytes} more were read. */
    public DataBudgetConsumption plus(long rows, long bytes) {
        return new DataBudgetConsumption(budgetId, name, maxRows, maxBytes, windowMinutes,
                breachAction, warnThresholdPercent, usedRows + rows, usedBytes + bytes);
    }

    private static double percent(long used, Long limit) {
        return limit == null ? 0d : used * 100d / limit;
    }
}
