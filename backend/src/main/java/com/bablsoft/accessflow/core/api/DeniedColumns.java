package com.bablsoft.accessflow.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The one matcher behind a permission's {@code denied_columns} (#935), shared by every gate that
 * refuses a query referencing a denied column — submission, the recurring recheck, break-glass,
 * dry-run, request groups and the access simulator — so they can never disagree.
 *
 * <p>An entry is {@code table.column} or {@code schema.table.column}. A wildcard on a table an entry
 * names is refused without consulting the schema: the denied column is in that table, so expanding
 * {@code *} would reach it. When both the entry and the referenced table carry a schema they must
 * match; when either lacks one, the table name alone decides, which fails closed.
 */
public final class DeniedColumns {

    private DeniedColumns() {
    }

    /** Strips identifier quotes, trims, lowercases and drops blanks. */
    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<String>(raw.size());
        for (String entry : raw) {
            var normalized = normalizeEntry(entry);
            if (normalized != null && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    /** {@code true} when the entry names a table and a column: two or three non-blank parts. */
    public static boolean isQualified(String entry) {
        var normalized = normalizeEntry(entry);
        if (normalized == null) {
            return false;
        }
        var parts = normalized.split("\\.", -1);
        if (parts.length < 2 || parts.length > 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the denied entries the parsed query reaches, sorted; empty when it reaches none. A
     *         data query whose columns were not analyzed (a non-JSqlParser engine) reaches every
     *         entry, so a deny list can never be silently skipped.
     */
    public static SortedSet<String> rejected(List<String> rawDenied, SqlParseResult parsed) {
        var denied = normalize(rawDenied);
        if (denied.isEmpty() || parsed == null || !isDataQuery(parsed.type())) {
            return new TreeSet<>();
        }
        if (!parsed.columnsAnalyzed()) {
            return new TreeSet<>(denied);
        }
        return rejected(denied, parsed.referencedColumns());
    }

    private static SortedSet<String> rejected(List<String> denied,
                                              Set<ColumnReference> references) {
        var out = new TreeSet<String>();
        for (String entry : denied) {
            var parts = entry.split("\\.");
            if (parts.length < 2) {
                continue;
            }
            var column = parts[parts.length - 1];
            var table = parts[parts.length - 2];
            var schema = parts.length > 2 ? parts[parts.length - 3] : null;
            for (ColumnReference reference : references) {
                if ((reference.isWildcard() || reference.column().equals(column))
                        && reachesTable(reference, schema, table)) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean reachesTable(ColumnReference reference, String schema, String table) {
        for (String candidate : reference.candidateTables()) {
            var parts = candidate.split("\\.");
            var candidateTable = parts[parts.length - 1];
            var candidateSchema = parts.length > 1 ? parts[parts.length - 2] : null;
            if (candidateTable.equals(table)
                    && (schema == null || candidateSchema == null
                        || candidateSchema.equals(schema))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDataQuery(QueryType type) {
        return type == QueryType.SELECT || type == QueryType.INSERT
                || type == QueryType.UPDATE || type == QueryType.DELETE;
    }

    private static String normalizeEntry(String entry) {
        if (entry == null) {
            return null;
        }
        var stripped = new StringBuilder(entry.length());
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c != '"' && c != '`' && c != '[' && c != ']') {
                stripped.append(c);
            }
        }
        var normalized = stripped.toString().trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
