package com.bablsoft.accessflow.lifecycle.api;

/**
 * Read-time pseudonymization transform applied to aged/erased columns. Modeled separately from
 * {@code core.api.MaskingStrategy} (per AF-499 design) and adapted to a {@code ColumnMaskDirective}
 * at directive-resolution time so the existing post-fetch masker applies it. Mirrors the PostgreSQL
 * {@code lifecycle_transform} enum.
 *
 * <ul>
 *   <li>{@code SHA256_SALTED} — deterministic SHA-256 over the value plus the per-org encrypted salt;
 *       irreversible, with salt rotation.</li>
 *   <li>{@code FORMAT_PRESERVING} — preserves the value's shape (length/charset) while replacing
 *       characters.</li>
 *   <li>{@code TOKENIZATION} — replaces the value with a stable opaque token.</li>
 * </ul>
 */
public enum LifecycleTransform {
    SHA256_SALTED,
    FORMAT_PRESERVING,
    TOKENIZATION
}
