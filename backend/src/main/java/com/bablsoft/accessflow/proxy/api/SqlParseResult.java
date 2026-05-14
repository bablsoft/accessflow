package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.util.List;

/**
 * Result of parsing a query submission. For single-statement input, {@code transactional} is
 * {@code false} and {@code statements} contains the original SQL verbatim. For
 * {@code BEGIN; INSERT…; INSERT…; COMMIT;} batches, {@code transactional} is {@code true},
 * {@code type} is the representative type (the first inner statement), and {@code statements}
 * holds the inner statement SQL slices (in submission order) so the executor can re-issue them
 * inside a single JDBC transaction.
 */
public record SqlParseResult(QueryType type, boolean transactional, List<String> statements) {

    public SqlParseResult {
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("statements must not be empty");
        }
        statements = List.copyOf(statements);
    }

    public SqlParseResult(QueryType type, String sql) {
        this(type, false, List.of(sql));
    }
}
