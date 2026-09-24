package com.bablsoft.accessflow.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one matcher behind a permission's {@code allowed_schemas} / {@code allowed_tables}, shared by
 * the query gates and the schema view (#936) so what a user can see never disagrees with what they
 * can query. Both lists empty means no restriction.
 */
public final class AllowedTables {

    private AllowedTables() {
    }

    /** Strips identifier quotes, trims, lowercases and drops blanks. */
    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<String>(raw.size());
        for (String entry : raw) {
            var normalized = normalizeEntry(entry);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    /** The {@link #normalize} rule for one name; {@code null} when blank. */
    public static String normalizeEntry(String entry) {
        if (entry == null) {
            return null;
        }
        var stripped = new StringBuilder(entry.length());
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c == '"' || c == '`' || c == '[' || c == ']') {
                continue;
            }
            stripped.append(c);
        }
        var normalized = stripped.toString().trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Which allow-list entry covers {@code table} — the qualified table itself, or the schema whose
     * prefix it carries — or {@code null} when none does. Both lists and {@code table} must already
     * be {@link #normalize}d.
     */
    public static String coveringEntry(List<String> allowedSchemas, List<String> allowedTables,
                                       String table) {
        if (allowedTables.contains(table)) {
            return table;
        }
        int dotIdx = table.indexOf('.');
        if (dotIdx > 0) {
            var schema = table.substring(0, dotIdx);
            if (allowedSchemas.contains(schema)) {
                return schema;
            }
        }
        return null;
    }
}
