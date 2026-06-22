package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared capability + allow-list checks for the standard query-submission gate and the break-glass
 * gate (AF-385). Both verify that a permission grants the capability for the parsed query type and
 * that every referenced table is within the permission's schema/table allow-list.
 */
final class DatasourcePermissionChecker {

    private DatasourcePermissionChecker() {
    }

    static boolean hasCapability(DatasourceUserPermissionView permission, QueryType type) {
        return switch (type) {
            case SELECT -> permission.canRead();
            case INSERT, UPDATE, DELETE -> permission.canWrite();
            case DDL -> permission.canDdl();
            case OTHER -> false;
        };
    }

    /**
     * @return the referenced tables not covered by the permission's allow-list, sorted; empty when
     *         the query is fully allowed (or the permission imposes no allow-list).
     */
    static Set<String> rejectedTables(DatasourceUserPermissionView permission,
                                      Set<String> referencedTables) {
        var allowedSchemas = normalizeList(permission.allowedSchemas());
        var allowedTables = normalizeList(permission.allowedTables());
        if (allowedSchemas.isEmpty() && allowedTables.isEmpty()) {
            return Set.of();
        }
        if (referencedTables == null || referencedTables.isEmpty()) {
            return Set.of();
        }
        var rejected = new TreeSet<String>();
        for (String table : referencedTables) {
            if (allowedTables.contains(table)) {
                continue;
            }
            int dotIdx = table.indexOf('.');
            if (dotIdx > 0 && allowedSchemas.contains(table.substring(0, dotIdx))) {
                continue;
            }
            rejected.add(table);
        }
        return rejected;
    }

    static List<String> normalizeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<String>(raw.size());
        for (String entry : raw) {
            if (entry == null) {
                continue;
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
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }
}
