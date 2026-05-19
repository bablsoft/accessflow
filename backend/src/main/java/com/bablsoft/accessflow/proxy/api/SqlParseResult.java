package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.util.List;
import java.util.Set;

/**
 * Result of parsing a query submission. For single-statement input, {@code transactional} is
 * {@code false} and {@code statements} contains the original SQL verbatim. For
 * {@code BEGIN; INSERT…; INSERT…; COMMIT;} batches, {@code transactional} is {@code true},
 * {@code type} is the representative type (the first inner statement), and {@code statements}
 * holds the inner statement SQL slices (in submission order) so the executor can re-issue them
 * inside a single JDBC transaction.
 *
 * <p>{@code referencedTables} is the union of tables touched by every parsed statement, walked
 * from the JSqlParser AST. Identifiers are normalized to lowercase, quotes stripped, and
 * formatted as {@code "schema.table"} when a schema qualifier is present or {@code "table"}
 * otherwise. CTE aliases are excluded. The set may be empty (e.g. {@code SELECT 1} or DDL
 * statements that JSqlParser cannot deparse) — callers must treat an empty set as "no tables
 * detected", not "allow everything".
 */
public record SqlParseResult(QueryType type, boolean transactional, List<String> statements,
                              Set<String> referencedTables) {

    public SqlParseResult {
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("statements must not be empty");
        }
        statements = List.copyOf(statements);
        referencedTables = referencedTables == null ? Set.of() : Set.copyOf(referencedTables);
    }

    public SqlParseResult(QueryType type, String sql) {
        this(type, false, List.of(sql), Set.of());
    }
}
