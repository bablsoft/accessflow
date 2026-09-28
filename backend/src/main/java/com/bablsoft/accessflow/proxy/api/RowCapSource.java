package com.bablsoft.accessflow.proxy.api;

/** The bound that set an {@link EffectiveRowCap} (#946). */
public enum RowCapSource {
    /** The smallest {@code row_limit_override} across the user's direct and group grants. */
    OVERRIDE,
    /** The datasource's {@code max_rows_per_query}. */
    DATASOURCE_CAP,
    /** The deployment-wide {@code accessflow.proxy.execution.max-rows} ceiling. */
    GLOBAL_CEILING
}
