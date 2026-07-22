package com.bablsoft.accessflow.engine.snowflake;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A (possibly qualified) table reference — {@code table}, {@code schema.table}, or
 * {@code db.schema.table}. Segments keep the case the tokenizer produced (quoted identifiers
 * verbatim, unquoted as written); {@link #normalized()} is the host allow-list form: quotes
 * stripped, lowercased, dot-joined. {@link #lastSegment()} is the bare table name used for
 * row-security directive matching.
 *
 * @param segments the reference's identifier segments in source order (never empty)
 */
record SnowflakeTableRef(List<String> segments) {

    SnowflakeTableRef {
        segments = List.copyOf(segments);
    }

    String normalized() {
        return segments.stream()
                .map(segment -> segment.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("."));
    }

    String lastSegment() {
        return segments.get(segments.size() - 1).toLowerCase(Locale.ROOT);
    }
}
