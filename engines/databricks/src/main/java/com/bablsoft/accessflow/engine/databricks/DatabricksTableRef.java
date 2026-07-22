package com.bablsoft.accessflow.engine.databricks;

import java.util.List;
import java.util.Locale;

/**
 * A (possibly Unity-Catalog-qualified) table reference: one to three dot-separated identifiers
 * ({@code table}, {@code schema.table}, or {@code catalog.schema.table}), each optionally
 * backtick-quoted in the source. Segments are stored with backticks stripped.
 *
 * @param segments the reference's identifier segments, in source order (never empty)
 */
record DatabricksTableRef(List<String> segments) {

    DatabricksTableRef {
        segments = List.copyOf(segments);
    }

    /** Lowercased dot-joined form ({@code catalog.schema.table} keeps its segments). */
    String normalized() {
        return String.join(".", segments).toLowerCase(Locale.ROOT);
    }

    /** The lowercased bare table name (last segment) — the row-security match key. */
    String lastSegment() {
        return segments.get(segments.size() - 1).toLowerCase(Locale.ROOT);
    }
}
