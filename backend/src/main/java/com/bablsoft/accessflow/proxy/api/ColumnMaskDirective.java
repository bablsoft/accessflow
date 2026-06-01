package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A resolved masking directive passed into query execution: the column to mask
 * ({@code columnRef}, matched against the result set with the same precedence as
 * {@code restricted_columns} — {@code schema.table.column} → {@code table.column} → bare column),
 * the {@link MaskingStrategy} to apply, its parameters, and the originating policy id (for audit).
 */
public record ColumnMaskDirective(
        String columnRef,
        MaskingStrategy strategy,
        Map<String, String> params,
        UUID policyId) {

    public ColumnMaskDirective {
        Objects.requireNonNull(columnRef, "columnRef");
        Objects.requireNonNull(strategy, "strategy");
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
