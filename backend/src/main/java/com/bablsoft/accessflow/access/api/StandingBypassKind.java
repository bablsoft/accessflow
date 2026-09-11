package com.bablsoft.accessflow.access.api;

/**
 * A path by which a user can reach data without appearing in any permission table (#968).
 *
 * <p>Declared in the order the report lists them. Kept apart from {@code workflow.api.AccessSourceKind}
 * — the same two origins seen per table on the effective-access explainer — because
 * {@code workflow} already depends on this module.
 */
public enum StandingBypassKind {
    /**
     * The user's effective role carries {@code Permission.QUERY_ADMIN}. The submission service
     * skips the per-datasource gate for such a user entirely, so they submit against any
     * datasource in the organization with zero {@code datasource_user_permissions} rows.
     */
    QUERY_ADMIN,
    /**
     * The user holds an unexpired {@code can_break_glass} grant — direct or inherited through a
     * group — on at least one active datasource (AF-385).
     */
    BREAK_GLASS
}
