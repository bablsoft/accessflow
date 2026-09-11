package com.bablsoft.accessflow.workflow.api;

/** Why one user can reach a table (issue AF-859). */
public enum AccessSourceKind {

    /** An unexpired {@code datasource_user_permissions} row. */
    DIRECT_PERMISSION,

    /** An unexpired group permission inherited through membership (AF-530). */
    GROUP_PERMISSION,

    /**
     * A direct row materialised from an approved JIT access request — a foreign key
     * ({@code datasource_user_permissions.access_grant_request_id}, #969), not a correlation.
     *
     * <p>{@link AccessSource#preApproveQueries()} says whether that grant is currently
     * {@code APPROVED}, unexpired and opted into query pre-approval (#582); a JIT row whose grant
     * has since expired, or was never opted in, keeps this label with the flag {@code false}.
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
