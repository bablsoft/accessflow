package com.bablsoft.accessflow.core.api;

/**
 * How a set of {@link RowSecurityDirective}s would apply to one query, determined <em>statically</em>
 * — without connecting to the datasource and without executing anything (issue AF-630).
 *
 * <p>The values are ordered from "the submitter still sees rows" to "the submitter sees nothing",
 * with {@link #UNKNOWN} deliberately outside that order: it means the engine could not decide, and
 * an unknown row must never be presented as safe.
 */
public enum RowSecurityOutcome {

    /** A predicate was spliced in; the query runs, filtered to the submitter's authorised rows. */
    APPLIED,

    /**
     * A directive resolved to no values (an unresolvable variable, or a user in no groups), so the
     * engine would emit an always-false predicate and the submitter would see nothing.
     */
    DENY_ALL,

    /**
     * The query references a policied table in a position the engine cannot provably filter, so it
     * would be rejected outright rather than run unfiltered — the runtime
     * {@link UnrewritableRowSecurityException} / HTTP 422 path.
     */
    FAIL_CLOSED,

    /** No directive targets anything this query references; row security does not change it. */
    NOT_APPLICABLE,

    /**
     * The engine cannot classify this query offline — for example Cassandra, whose predicate
     * splicing depends on partition / clustering key names that only a live session knows. Never
     * treat this as equivalent to {@link #NOT_APPLICABLE}.
     */
    UNKNOWN
}
