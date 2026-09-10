package com.bablsoft.accessflow.workflow.api;

/** Why one user can reach a table (issue AF-859). */
public enum AccessSourceKind {

    /** An unexpired {@code datasource_user_permissions} row. */
    DIRECT_PERMISSION,

    /** An unexpired group permission inherited through membership (AF-530). */
    GROUP_PERMISSION,

    /**
     * A time-boxed direct row correlated to an active JIT grant that pre-approves queries (#582).
     *
     * <p>A correlation, not a foreign key: a JIT grant is materialised as an ordinary permission row
     * and the originating request id is not recorded on it, so this label is inferred from an active
     * pre-approving grant for the same user and datasource. The expiry is the fact; the label is the
     * hint.
     */
    JIT_GRANT,

    /**
     * The user holds {@code QUERY_ADMIN}, which skips the per-datasource gate outright. Such a user
     * reaches every table with no permission row at all — the row no other screen shows, and the
     * reason this is a source rather than a flag.
     */
    QUERY_ADMIN_BYPASS,

    /**
     * The user holds {@code can_break_glass} here. Never counts towards {@code granted}: break-glass
     * is a separate submission mode with its own compensating controls, not a capability on the
     * ordinary path.
     */
    BREAK_GLASS
}
