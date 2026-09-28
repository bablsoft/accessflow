package com.bablsoft.accessflow.proxy.api;

/** The bound that set an {@link EffectiveRowCap} (#946). */
public enum RowCapSource {
    /** The smallest {@code row_limit_override} across the user's direct and group grants. */
    OVERRIDE,
    /** The datasource's {@code max_rows_per_query}. */
    DATASOURCE_CAP,
    /** The deployment-wide {@code accessflow.proxy.execution.max-rows} ceiling. */
    GLOBAL_CEILING,
    /**
     * A per-table row-limit policy (#934) on a table the query references. Only a caller that knows
     * the query's tables reports it — the access simulator; {@link EffectiveRowCap#of} never does.
     */
    ROW_LIMIT_POLICY
}
