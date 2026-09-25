package com.bablsoft.accessflow.core.api;

import java.util.ArrayList;
import java.util.HashSet;
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
     *         entry, so a deny list can never be silently skipped. DDL is refused when it reads a
     *         denied column through an embedded query ({@code CREATE TABLE … AS SELECT}) or touches
     *         a table a denied entry names at all; {@code OTHER} is never checked, since no
     *         permission grants it.
     */
    public static SortedSet<String> rejected(List<String> rawDenied, SqlParseResult parsed) {
        var denied = normalize(rawDenied);
        if (denied.isEmpty() || parsed == null || parsed.type() == QueryType.OTHER) {
            return new TreeSet<>();
        }
        if (!parsed.columnsAnalyzed()) {
            return new TreeSet<>(denied);
        }
        var out = rejected(denied, parsed.referencedColumns());
        if (parsed.type() == QueryType.DDL) {
            // DDL can expose a column without reading it (RENAME COLUMN, a generated column, a view
            // over it), so DDL touching a table with a denied column is refused outright.
            var touched = new HashSet<ColumnReference>();
            for (String table : parsed.referencedTables()) {
                touched.add(ColumnReference.wildcard(Set.of(table)));
            }
            out.addAll(rejected(denied, touched));
        }
        return out;
    }

    /**
     * The columns either deny list denies, deduplicated by what each entry matches rather than by
     * its spelling: {@code users.ssn} already denies {@code public.users.ssn} (an entry without a
     * schema matches the table in every schema), so the union keeps only {@code users.ssn}. Used to
     * merge a user's grants, where a column stays denied if any grant denies it (#1099).
     */
    public static List<String> union(List<String> left, List<String> right) {
        var all = new ArrayList<String>(normalize(left));
        for (String entry : normalize(right)) {
            if (!all.contains(entry)) {
                all.add(entry);
            }
        }
        var out = new ArrayList<String>(all.size());
        for (String entry : all) {
            if (all.stream().noneMatch(other -> covers(other, entry))) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /** {@code true} when {@code broader} is {@code table.column} and {@code narrower} pins a schema. */
    private static boolean covers(String broader, String narrower) {
        var pb = broader.split("\\.");
        var pn = narrower.split("\\.");
        return pb.length == 2 && pn.length == 3
                && pb[0].equals(pn[1]) && pb[1].equals(pn[2]);
    }

    /**
     * @return the denied entries a read of every column of {@code table} ({@code schema.table} or
     *         {@code table}) would reach — the table preview, which runs {@code SELECT *}
     */
    public static SortedSet<String> rejectedForWholeTable(List<String> rawDenied, String table) {
        var denied = normalize(rawDenied);
        if (denied.isEmpty() || table == null || table.isBlank()) {
            return new TreeSet<>();
        }
        return rejected(denied, Set.of(ColumnReference.wildcard(Set.of(normalizeEntry(table)))));
    }

    /**
     * @return whether the deny list denies {@code column} of the introspected table — the schema
     *         view (#936) hides such columns from a restricted caller. {@code schema} may be null.
     */
    public static boolean deniesColumn(List<String> rawDenied, String schema, String table,
                                       String column) {
        var denied = normalize(rawDenied);
        var normalizedTable = normalizeEntry(table);
        var normalizedColumn = normalizeEntry(column);
        if (denied.isEmpty() || normalizedTable == null || normalizedColumn == null) {
            return false;
        }
        var normalizedSchema = normalizeEntry(schema);
        var qualified = normalizedSchema == null
                ? normalizedTable
                : normalizedSchema + "." + normalizedTable;
        return !rejected(denied,
                Set.of(new ColumnReference(Set.of(qualified), normalizedColumn))).isEmpty();
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
