package com.bablsoft.accessflow.core.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Signals that {@code DELETE} statements against {@code tableRef} must be rewritten by the proxy into
 * a soft delete — {@code UPDATE <tableRef> SET <markerColumn> = CURRENT_TIMESTAMP} — rather than
 * physically removing rows (AF-499). Read filtering of already-soft-deleted rows is handled
 * separately by an {@code IS_NULL} {@link RowSecurityDirective} on the same marker column.
 * {@code policyId} is recorded for audit.
 */
public record SoftDeleteDirective(UUID policyId, String tableRef, String markerColumn) {

    public SoftDeleteDirective {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(tableRef, "tableRef");
        Objects.requireNonNull(markerColumn, "markerColumn");
    }
}
