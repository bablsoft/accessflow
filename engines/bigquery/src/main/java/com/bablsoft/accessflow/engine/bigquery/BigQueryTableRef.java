package com.bablsoft.accessflow.engine.bigquery;

/**
 * A table referenced by a GoogleSQL statement, normalized to its lowercase dot-joined path
 * ({@code project.dataset.table}, {@code dataset.table}, or bare {@code table} when the default
 * dataset supplies the rest). Backticks are stripped during parsing — including the GoogleSQL form
 * where a single backtick pair spans the whole path ({@code `project.dataset.table`}).
 *
 * @param normalized the lowercase dot-joined table path (never {@code null})
 */
record BigQueryTableRef(String normalized) {

    /** The bare table name (the path's last segment) — the row-security matching key. */
    String lastSegment() {
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }
}
