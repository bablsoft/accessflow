package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a replay target datasource contains every table the snapshotted query references
 * (AF-449). The parser emits {@code referencedTables} already normalized (lowercase, quotes stripped,
 * {@code schema.table} when qualified else bare {@code table}); this matcher normalizes the introspected
 * {@link DatabaseSchemaView} the same way and reports any referenced table missing from the target.
 */
final class ReplaySchemaMatcher {

    private ReplaySchemaMatcher() {
    }

    /**
     * Returns the referenced tables (in input order, de-duplicated) that are NOT present in the target
     * schema. An empty result means the target is table-compatible. A qualified {@code schema.table}
     * reference must match an exact {@code schema.table} in the target; a bare {@code table} reference
     * matches if any schema in the target has that table.
     */
    static List<String> missingTables(List<String> referencedTables, DatabaseSchemaView targetSchema) {
        if (referencedTables == null || referencedTables.isEmpty()) {
            return List.of();
        }
        Set<String> qualified = new HashSet<>();
        Set<String> bare = new HashSet<>();
        if (targetSchema != null && targetSchema.schemas() != null) {
            for (var schema : targetSchema.schemas()) {
                var schemaName = lower(schema.name());
                if (schema.tables() == null) {
                    continue;
                }
                for (var table : schema.tables()) {
                    var tableName = lower(table.name());
                    bare.add(tableName);
                    qualified.add(schemaName + "." + tableName);
                }
            }
        }
        var missing = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (String raw : referencedTables) {
            var ref = lower(raw);
            if (ref.isEmpty() || !seen.add(ref)) {
                continue;
            }
            boolean present = ref.indexOf('.') >= 0
                    ? qualified.contains(ref)
                    : bare.contains(ref);
            if (!present) {
                missing.add(ref);
            }
        }
        return List.copyOf(missing);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
