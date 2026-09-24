package com.bablsoft.accessflow.core.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The one matcher behind a permission's {@code denied_schemas} / {@code denied_tables} (#939), shared
 * by the query gates, the dry-run, the table preview, request groups and the schema view, so they
 * never disagree about a schema-qualified name. A denial always beats the allow-list.
 *
 * <p>Every rule fails closed, because the gate cannot know where the database resolves a name:
 * a {@code denied_tables} entry matches a reference when either name is a dot-aligned suffix of the
 * other ({@code salary} denies {@code crm.salary}; {@code crm.salary} denies a bare {@code salary}),
 * and a {@code denied_schemas} entry matches any qualified reference carrying it as a non-final
 * segment, and every unqualified reference — so the schema view can list {@code public.orders} while
 * a bare {@code FROM orders} is refused: under a schema denial, users must schema-qualify.
 */
public final class DeniedTables {

    /** One name, no dots and no wildcards: a pattern here could never match a segment. */
    public static final String SCHEMA_ENTRY_PATTERN = "^[^.*?]*[^.*?\\s][^.*?]*$";

    /** {@code table} or {@code schema.table} (any depth), or a whole schema as {@code schema.*}. */
    public static final String TABLE_ENTRY_PATTERN =
            "^[^.*?]*[^.*?\\s][^.*?]*(\\.[^.*?]*[^.*?\\s][^.*?]*)*(\\.\\*)?$";

    private DeniedTables() {
    }

    /** Whether {@code entry} is a well-formed {@code denied_schemas} entry. */
    public static boolean isValidSchemaEntry(String entry) {
        return entry != null && entry.matches(SCHEMA_ENTRY_PATTERN);
    }

    /** Whether {@code entry} is a well-formed {@code denied_tables} entry. */
    public static boolean isValidTableEntry(String entry) {
        return entry != null && entry.matches(TABLE_ENTRY_PATTERN);
    }

    /** {@link AllowedTables#normalize}, with duplicates dropped. */
    public static List<String> normalize(List<String> raw) {
        var out = new ArrayList<String>();
        for (String entry : AllowedTables.normalize(raw)) {
            if (!out.contains(entry)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Which entry denies {@code table}, or {@code null} when none does. Both lists and {@code table}
     * must already be {@link #normalize}d.
     *
     * <p>Names are compared segment by segment from the right. A reference segment the database
     * would fill in itself — the empty schema of SQL Server's {@code db..table} — matches anything,
     * an Oracle {@code @dblink} suffix is ignored, and a reference that is a pattern rather than a
     * name (an Elasticsearch {@code sal*} index) is denied by any entry at all, since it may expand
     * to a denied object.
     */
    public static String denyingEntry(List<String> deniedSchemas, List<String> deniedTables,
                                      String table) {
        if (table == null || (deniedSchemas.isEmpty() && deniedTables.isEmpty())) {
            return null;
        }
        var reference = segments(table);
        if (isPattern(table)) {
            return deniedTables.isEmpty() ? deniedSchemas.get(0) : deniedTables.get(0);
        }
        var schemas = new ArrayList<>(deniedSchemas);
        for (String entry : deniedTables) {
            if (entry.endsWith(".*")) {
                // "crm.*" in the table list means the schema — the way the UI displays one.
                schemas.add(entry.substring(0, entry.length() - 2));
            } else if (alignedFromTheRight(segments(entry), reference)) {
                return entry;
            }
        }
        return schemaDenial(schemas, reference);
    }

    private static String schemaDenial(List<String> deniedSchemas, String[] reference) {
        if (deniedSchemas.isEmpty()) {
            return null;
        }
        if (reference.length == 1) {
            // Unqualified: the gate cannot tell which schema it resolves to.
            return deniedSchemas.get(0);
        }
        for (int i = 0; i < reference.length - 1; i++) {
            if (reference[i].isEmpty()) {
                return deniedSchemas.get(0);
            }
            if (deniedSchemas.contains(reference[i])) {
                return reference[i];
            }
        }
        return null;
    }

    /**
     * Whether the shorter name is the tail of the longer one: {@code salary} and {@code crm.salary}
     * align, so do {@code crm.salary} and {@code db.crm.salary}. An empty reference segment matches
     * any entry segment.
     */
    private static boolean alignedFromTheRight(String[] entry, String[] reference) {
        int overlap = Math.min(entry.length, reference.length);
        for (int i = 1; i <= overlap; i++) {
            var referenceSegment = reference[reference.length - i];
            if (!referenceSegment.isEmpty() && !referenceSegment.equals(entry[entry.length - i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] segments(String name) {
        var at = name.indexOf('@');
        var withoutLink = at > 0 ? name.substring(0, at) : name;
        return withoutLink.split("\\.", -1);
    }

    private static boolean isPattern(String reference) {
        return reference.indexOf('*') >= 0 || reference.indexOf('?') >= 0;
    }

    /** @return the referenced tables a denial reaches, sorted; empty when none is denied. */
    public static SortedSet<String> rejected(List<String> rawDeniedSchemas,
                                             List<String> rawDeniedTables,
                                             Collection<String> referencedTables) {
        var out = new TreeSet<String>();
        var deniedSchemas = normalize(rawDeniedSchemas);
        var deniedTables = normalize(rawDeniedTables);
        if ((deniedSchemas.isEmpty() && deniedTables.isEmpty()) || referencedTables == null) {
            return out;
        }
        for (String table : referencedTables) {
            if (denyingEntry(deniedSchemas, deniedTables, AllowedTables.normalizeEntry(table))
                    != null) {
                out.add(table);
            }
        }
        return out;
    }

    /**
     * @return whether the introspected {@code schema.table} is denied — the schema view and the
     *         table preview. {@code schema} may be null, which reads as an unqualified reference.
     */
    public static boolean deniesTable(List<String> rawDeniedSchemas, List<String> rawDeniedTables,
                                      String schema, String table) {
        var bare = AllowedTables.normalizeEntry(table);
        if (bare == null) {
            return false;
        }
        var normalizedSchema = AllowedTables.normalizeEntry(schema);
        var qualified = normalizedSchema == null ? bare : normalizedSchema + "." + bare;
        return denyingEntry(normalize(rawDeniedSchemas), normalize(rawDeniedTables), qualified)
                != null;
    }

    /** @return whether a whole schema is denied, so the schema view never lists it. */
    public static boolean deniesSchema(List<String> rawDeniedSchemas, String schema) {
        var normalized = AllowedTables.normalizeEntry(schema);
        return normalized != null && normalize(rawDeniedSchemas).contains(normalized);
    }
}
