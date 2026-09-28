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
    FORMAT_PRESERVING,
    /**
     * Regex replacement (params {@code pattern}, {@code replacement} with {@code $n} /
     * {@code ${name}} group references). A value the pattern does not match is fully masked.
     */
    REGEX_REPLACE,
    /** Replace every value with a fixed string (param {@code replacement}). */
    CONSTANT,
    /** Replace every value with {@code null} — distinct from {@link #FULL}'s sentinel. */
    NULLIFY,
    /** Keep the first N characters (param {@code visible_prefix}); mask the rest. */
    KEEP_FIRST,
    /**
     * Generalise a number to a bucket: param {@code bucket_size} (floor to a multiple) or
     * {@code boundaries} (ascending comma list). A non-numeric value is fully masked.
     */
    NUMERIC_BUCKET,
    /**
     * Truncate a date to param {@code precision} ({@code YEAR} / {@code QUARTER} /
     * {@code MONTH}). A value without a leading {@code yyyy-MM} is fully masked.
     */
    DATE_GENERALIZE
}
