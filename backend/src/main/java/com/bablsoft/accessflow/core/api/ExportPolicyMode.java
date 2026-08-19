package com.bablsoft.accessflow.core.api;

/**
 * How a query's persisted result set may leave AccessFlow (#626). Values are ordered by
 * restrictiveness — when several policies apply to an exporter, the highest-ordinal mode wins
 * ({@code ALLOW < WATERMARK < ROW_CAP < DENY_CLASSIFIED}), so the enum order is load-bearing.
 */
public enum ExportPolicyMode {

    /** Export freely, unwatermarked. Also the implicit default when no policy applies. */
    ALLOW,

    /** Export carries a watermark (exporter email, UTC timestamp, query request id). */
    WATERMARK,

    /** Export is truncated to the policy's row cap; capped exports are always watermarked. */
    ROW_CAP,

    /**
     * Export is denied when the result contains a column classified with one of the policy's
     * {@code deny_classifications} (empty = any classification). Without a matching classified
     * column the policy does not participate in the mode combination at all.
     */
    DENY_CLASSIFIED
}
