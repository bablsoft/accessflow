package com.bablsoft.accessflow.core.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One delivered SELECT result to charge against a user's data budgets (#942): the rows and
 * estimated result bytes the user actually received, after row security and every cap.
 */
public record DataBudgetUsageRecord(
        UUID userId,
        UUID datasourceId,
        long rowsRead,
        long bytesRead,
        DataBudgetUsageSource source,
        UUID queryRequestId,
        UUID requestGroupId) {

    public DataBudgetUsageRecord {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(datasourceId, "datasourceId");
        Objects.requireNonNull(source, "source");
        rowsRead = Math.max(0, rowsRead);
        bytesRead = Math.max(0, bytesRead);
    }
}
