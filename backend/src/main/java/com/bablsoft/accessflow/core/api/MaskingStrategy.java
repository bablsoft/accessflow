package com.bablsoft.accessflow.core.api;

/**
 * How a policied column's value is rendered when masking applies. Mirrors the PostgreSQL
 * {@code masking_strategy} enum. {@link #FULL} reproduces today's static {@code restricted_columns}
 * behaviour; the rest are dynamic per-value transformations applied at result-read time.
 */
public enum MaskingStrategy {
    /** Replace the whole value with a fixed mask token. */
    FULL,
    /** Keep the last N characters (param {@code visible_suffix}); mask the rest. */
    PARTIAL,
    /** Replace with a deterministic SHA-256 hex digest of the value. */
    HASH,
    /** Preserve the first local-part character and the domain: {@code j***@example.com}. */
    EMAIL,
    /** Preserve length/shape: digits and letters are replaced, separators are kept. */
    FORMAT_PRESERVING
}
