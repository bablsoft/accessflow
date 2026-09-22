package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;

import java.util.Objects;
import java.util.UUID;

/**
 * One deterministic-SQL-review finding raised by the authoring gate (#879) on one statement of a
 * change set against one target datasource's ruleset. {@code statementIndex} is the zero-based
 * position in the change set (the wrapped finding's own index is always 0 — each statement is
 * evaluated alone); the text is never carried here — it is rendered per reader from the wrapped
 * finding's rule id and args.
 */
public record SchemaChangeStatementFinding(int statementIndex, UUID datasourceId, SqlReviewFinding finding) {

    public SchemaChangeStatementFinding {
        Objects.requireNonNull(datasourceId, "datasourceId");
        Objects.requireNonNull(finding, "finding");
    }

    public boolean isBlocking() {
        return finding.isBlocking();
    }
}
