package com.bablsoft.accessflow.core.api;

/**
 * Produces a normalised "canonical" form of a SQL string for matching repeated runs
 * of the same query. The transformation strips comments, collapses whitespace, and
 * upper-cases the result so cosmetic edits (added comments, reformatting, casing
 * tweaks) do not break the link between successive submissions.
 *
 * <p>The output is opaque — it is intended only for equality comparisons against
 * other canonical outputs, never for re-execution against a database.
 */
public interface SqlCanonicalizer {

    /**
     * Returns the canonical form of {@code sql}, or {@code null} when {@code sql}
     * is null or blank (the caller treats that as "no key, skip linking").
     */
    String canonicalize(String sql);
}
