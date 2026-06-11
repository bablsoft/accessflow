package com.bablsoft.accessflow.engine.couchbase;

import java.util.List;
import java.util.Locale;

/**
 * A keyspace path referenced by a SQL++ statement — one part ({@code collection}, resolved inside
 * the datasource bucket's default scope at execution time) or three
 * ({@code bucket.scope.collection}). Parts are stored lowercased with backticks already stripped;
 * {@link #normalized()} is the dotted form carried in {@code SqlParseResult.referencedTables} and
 * matched against permission grants (exact full path, or {@code allowedSchemas} prefix on the
 * first segment — see docs/14-connectors.md).
 */
record KeyspaceRef(List<String> parts) {

    KeyspaceRef {
        parts = parts.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    String normalized() {
        return String.join(".", parts);
    }

    /** The collection segment — what collection-level grants and RLS table refs match against. */
    String lastSegment() {
        return parts.get(parts.size() - 1);
    }
}
