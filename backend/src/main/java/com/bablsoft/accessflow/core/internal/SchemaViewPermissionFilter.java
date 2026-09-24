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
 * query gates enforce. Where the view cannot tell what the gate would allow it fails closed: a bare
 * {@code allowed_tables} entry names whatever table the database resolves the unqualified name to,
 * so it only shows a table whose name is unique in the view, and a foreign key whose bare target name
 * also belongs to a hidden table is dropped.
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

        var ambiguousNames = ambiguousTableNames(view);
        var visible = new ArrayList<DatabaseSchemaView.Schema>();
        var visibleTableNames = new HashSet<String>();
        var hiddenTableNames = new HashSet<String>();
        for (var schema : view.schemas()) {
            var normalizedSchema = AllowedTables.normalizeEntry(schema.name());
            var tables = new ArrayList<DatabaseSchemaView.Table>();
            for (var table : nullSafe(schema.tables())) {
                var bare = AllowedTables.normalizeEntry(table.name());
                if (!restricted || tableAllowed(allowedSchemas, allowedTables, normalizedSchema,
                        bare, ambiguousNames)) {
                    tables.add(table);
                    if (bare != null) {
                        visibleTableNames.add(bare);
                    }
                } else if (bare != null) {
                    hiddenTableNames.add(bare);
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
                tables.add(narrowTable(schema.name(), table, denied, restricted, visibleTableNames,
                        hiddenTableNames));
            }
            out.add(new DatabaseSchemaView.Schema(schema.name(), List.copyOf(tables)));
        }
        return new DatabaseSchemaView(List.copyOf(out));
    }

    private static boolean tableAllowed(List<String> allowedSchemas, List<String> allowedTables,
                                        String normalizedSchema, String bare,
                                        Set<String> ambiguousNames) {
        if (bare == null) {
            return false;
        }
        if (allowedTables.contains(bare) && !ambiguousNames.contains(bare)) {
            return true;
        }
        if (normalizedSchema == null) {
            return false;
        }
        var qualified = normalizedSchema + "." + bare;
        if (AllowedTables.coveringEntry(allowedSchemas, allowedTables, qualified) != null) {
            return true;
        }
        // A catalog-qualified entry (project.dataset.table, db.schema.table) covers the table the
        // view reports under its trailing schema.table.
        var suffix = "." + qualified;
        for (String entry : allowedTables) {
            if (entry.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> ambiguousTableNames(DatabaseSchemaView view) {
        var seen = new HashSet<String>();
        var ambiguous = new HashSet<String>();
        for (var schema : view.schemas()) {
            for (var table : nullSafe(schema.tables())) {
                var bare = AllowedTables.normalizeEntry(table.name());
                if (bare != null && !seen.add(bare)) {
                    ambiguous.add(bare);
                }
            }
        }
        return ambiguous;
    }

    private static DatabaseSchemaView.Table narrowTable(String schema, DatabaseSchemaView.Table table,
                                                        List<String> denied, boolean restricted,
                                                        Set<String> visibleTableNames,
                                                        Set<String> hiddenTableNames) {
        var columns = new ArrayList<DatabaseSchemaView.Column>();
        for (var column : nullSafe(table.columns())) {
            if (!DeniedColumns.deniesColumn(denied, schema, table.name(), column.name())) {
                columns.add(column);
            }
        }
        var foreignKeys = new ArrayList<DatabaseSchemaView.ForeignKey>();
        for (var fk : nullSafe(table.foreignKeys())) {
            if (fkVisible(schema, table.name(), fk, denied, restricted, visibleTableNames,
                    hiddenTableNames)) {
                foreignKeys.add(fk);
            }
        }
        return new DatabaseSchemaView.Table(table.name(), List.copyOf(columns),
                List.copyOf(foreignKeys));
    }

    private static boolean fkVisible(String schema, String table, DatabaseSchemaView.ForeignKey fk,
                                     List<String> denied, boolean restricted,
                                     Set<String> visibleTableNames,
                                     Set<String> hiddenTableNames) {
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
        // toTable carries no schema, so a name shared with a hidden table could point at it.
        var target = AllowedTables.normalizeEntry(fk.toTable());
        return target != null && visibleTableNames.contains(target)
                && !hiddenTableNames.contains(target);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
