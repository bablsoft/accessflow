package com.bablsoft.accessflow.core.api;

import java.util.Set;

/**
 * One column a parsed statement references, for column-level authorization (#935).
 *
 * <p>{@code candidateTables} are the real tables the column may belong to, normalized like
 * {@link SqlParseResult#referencedTables()} ({@code "schema.table"} or {@code "table"}). A
 * qualified column resolves to one table; an unqualified column in a multi-table query carries
 * every table it could belong to, so a check over it fails closed. {@code column} is the
 * lowercase, unquoted column name, or {@link #WILDCARD} for {@code *}, {@code t.*} and an
 * {@code INSERT} without a column list.
 */
public record ColumnReference(Set<String> candidateTables, String column) {

    public static final String WILDCARD = "*";

    public ColumnReference {
        candidateTables = candidateTables == null ? Set.of() : Set.copyOf(candidateTables);
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
    }

    public static ColumnReference wildcard(Set<String> candidateTables) {
        return new ColumnReference(candidateTables, WILDCARD);
    }

    public boolean isWildcard() {
        return WILDCARD.equals(column);
    }
}
