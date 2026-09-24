package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AllowedTables;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DeniedColumns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Narrows an introspected schema to what a restricted caller may query (#936): tables outside the
 * allow-list, columns on the deny list, and foreign keys that would name a hidden table or column
 * are dropped. Matching goes through {@link AllowedTables} and {@link DeniedColumns}, the rules the
 * query gates enforce, so the view never shows less or more than a query can reach.
 */
final class SchemaViewPermissionFilter {

    private SchemaViewPermissionFilter() {
    }

    static DatabaseSchemaView apply(DatabaseSchemaView view, DatasourceUserPermissionView permission) {
        if (view == null || view.schemas() == null) {
            return view;
        }
        var allowedSchemas = AllowedTables.normalize(permission.allowedSchemas());
        var allowedTables = AllowedTables.normalize(permission.allowedTables());
        var restricted = !allowedSchemas.isEmpty() || !allowedTables.isEmpty();
        var denied = permission.deniedColumns();

        var visible = new ArrayList<DatabaseSchemaView.Schema>();
        var visibleTableNames = new HashSet<String>();
        for (var schema : view.schemas()) {
            var normalizedSchema = AllowedTables.normalizeEntry(schema.name());
            var tables = new ArrayList<DatabaseSchemaView.Table>();
            for (var table : nullSafe(schema.tables())) {
                if (!restricted || tableAllowed(allowedSchemas, allowedTables, normalizedSchema,
                        table.name())) {
                    tables.add(table);
                    var bare = AllowedTables.normalizeEntry(table.name());
                    if (bare != null) {
                        visibleTableNames.add(bare);
                    }
                }
            }
            if (!tables.isEmpty()
                    || (normalizedSchema != null && allowedSchemas.contains(normalizedSchema))
                    || !restricted) {
                visible.add(new DatabaseSchemaView.Schema(schema.name(), tables));
            }
        }

        var out = new ArrayList<DatabaseSchemaView.Schema>(visible.size());
        for (var schema : visible) {
            var tables = new ArrayList<DatabaseSchemaView.Table>(schema.tables().size());
            for (var table : schema.tables()) {
                tables.add(narrowTable(schema.name(), table, denied, restricted, visibleTableNames));
            }
            out.add(new DatabaseSchemaView.Schema(schema.name(), List.copyOf(tables)));
        }
        return new DatabaseSchemaView(List.copyOf(out));
    }

    private static boolean tableAllowed(List<String> allowedSchemas, List<String> allowedTables,
                                        String normalizedSchema, String tableName) {
        var bare = AllowedTables.normalizeEntry(tableName);
        if (bare == null) {
            return false;
        }
        if (allowedTables.contains(bare)) {
            return true;
        }
        if (normalizedSchema == null) {
            return false;
        }
        return AllowedTables.coveringEntry(allowedSchemas, allowedTables,
                normalizedSchema + "." + bare) != null;
    }

    private static DatabaseSchemaView.Table narrowTable(String schema, DatabaseSchemaView.Table table,
                                                        List<String> denied, boolean restricted,
                                                        Set<String> visibleTableNames) {
        var columns = new ArrayList<DatabaseSchemaView.Column>();
        for (var column : nullSafe(table.columns())) {
            if (!DeniedColumns.deniesColumn(denied, schema, table.name(), column.name())) {
                columns.add(column);
            }
        }
        var foreignKeys = new ArrayList<DatabaseSchemaView.ForeignKey>();
        for (var fk : nullSafe(table.foreignKeys())) {
            if (fkVisible(schema, table.name(), fk, denied, restricted, visibleTableNames)) {
                foreignKeys.add(fk);
            }
        }
        return new DatabaseSchemaView.Table(table.name(), List.copyOf(columns),
                List.copyOf(foreignKeys));
    }

    private static boolean fkVisible(String schema, String table, DatabaseSchemaView.ForeignKey fk,
                                     List<String> denied, boolean restricted,
                                     Set<String> visibleTableNames) {
        if (DeniedColumns.deniesColumn(denied, schema, table, fk.fromColumn())) {
            return false;
        }
        // The introspector reports the referenced table by bare name only, so the referenced
        // column is checked against that name; a schema-qualified deny entry fails closed here.
        if (DeniedColumns.deniesColumn(denied, null, fk.toTable(), fk.toColumn())) {
            return false;
        }
        if (!restricted) {
            return true;
        }
        var target = AllowedTables.normalizeEntry(fk.toTable());
        return target != null && visibleTableNames.contains(target);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
