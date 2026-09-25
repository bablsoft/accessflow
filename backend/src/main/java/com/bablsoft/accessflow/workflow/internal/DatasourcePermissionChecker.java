package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.AllowedTables;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DeniedColumns;
import com.bablsoft.accessflow.core.api.DeniedShapes;
import com.bablsoft.accessflow.core.api.DeniedTables;
import com.bablsoft.accessflow.core.api.QueryShape;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared capability + allow-list checks for the standard query-submission gate and the break-glass
 * gate (AF-385). Both verify that a permission grants the capability for the parsed query type and
 * that every referenced table is within the permission's schema/table allow-list and outside its
 * schema/table deny-lists (#939), and that no referenced column is on the permission's deny list
 * (#935).
 */
final class DatasourcePermissionChecker {

    private DatasourcePermissionChecker() {
    }

    static boolean hasCapability(DatasourceUserPermissionView permission, QueryType type) {
        return hasCapability(permission.canRead(), permission.canWrite(), permission.canDdl(),
                type);
    }

    static boolean hasCapability(boolean canRead, boolean canWrite, boolean canDdl,
                                 QueryType type) {
        return switch (type) {
            case SELECT -> canRead;
            case INSERT, UPDATE, DELETE -> canWrite;
            case DDL -> canDdl;
            case OTHER -> false;
        };
    }

    /**
     * @return the referenced tables not covered by the permission's allow-list, sorted; empty when
     *         the query is fully allowed (or the permission imposes no allow-list).
     */
    static Set<String> rejectedTables(DatasourceUserPermissionView permission,
                                      Set<String> referencedTables) {
        return rejectedTables(permission.allowedSchemas(), permission.allowedTables(),
                referencedTables);
    }

    static Set<String> rejectedTables(List<String> rawAllowedSchemas,
                                      List<String> rawAllowedTables,
                                      Set<String> referencedTables) {
        var allowedSchemas = normalizeList(rawAllowedSchemas);
        var allowedTables = normalizeList(rawAllowedTables);
        if (allowedSchemas.isEmpty() && allowedTables.isEmpty()) {
            return Set.of();
        }
        if (referencedTables == null || referencedTables.isEmpty()) {
            return Set.of();
        }
        var rejected = new TreeSet<String>();
        for (String table : referencedTables) {
            if (coveringEntry(allowedSchemas, allowedTables, table) == null) {
                rejected.add(table);
            }
        }
        return rejected;
    }

    /**
     * @return the referenced tables the permission's {@code denied_schemas} / {@code denied_tables}
     *         reach (#939), sorted; a denial applies whether or not an allow-list is set.
     */
    static Set<String> deniedTables(DatasourceUserPermissionView permission,
                                    Set<String> referencedTables) {
        return DeniedTables.rejected(permission.deniedSchemas(), permission.deniedTables(),
                referencedTables);
    }

    /**
     * @return every referenced table the permission does not let through — outside the allow-list
     *         or denied — sorted. The one answer for callers that only need "may this user reach it".
     */
    static Set<String> blockedTables(DatasourceUserPermissionView permission,
                                     Set<String> referencedTables) {
        var blocked = new TreeSet<>(rejectedTables(permission, referencedTables));
        blocked.addAll(deniedTables(permission, referencedTables));
        return blocked;
    }

    /**
     * @return the permission's {@code denied_columns} entries the parsed query reaches, sorted;
     *         empty when it reaches none. Fails closed over a parse that did not analyze columns.
     */
    static Set<String> rejectedColumns(DatasourceUserPermissionView permission,
                                       SqlParseResult parsed) {
        return DeniedColumns.rejected(permission.deniedColumns(), parsed);
    }

    /**
     * @return the permission's {@code denied_shapes} the parsed query has (#940), in declaration
     *         order; empty when it has none. Fails closed over a parse whose shape was not analyzed.
     */
    static Set<QueryShape> rejectedShapes(DatasourceUserPermissionView permission,
                                          SqlParseResult parsed) {
        return DeniedShapes.rejected(permission.deniedShapes(), parsed);
    }

    /** The shape names for a message or trace detail, in declaration order. */
    static List<String> shapeNames(Set<QueryShape> shapes) {
        return shapes.stream().sorted().map(QueryShape::name).toList();
    }

    /**
     * Which allow-list entry covers {@code table} — the qualified table itself, or the schema whose
     * prefix it carries — or {@code null} when none does.
     *
     * <p>The reverse index (AF-859) needs to name the covering entry, not just know one exists, and
     * this is the branch {@link #rejectedTables} makes its decision on. One rule, so a report can
     * never disagree with the gate about which grant lets a query through.
     *
     * <p>Both lists must already be {@link #normalizeList}d, and so must {@code table}.
     */
    static String coveringEntry(List<String> allowedSchemas, List<String> allowedTables,
                                String table) {
        return AllowedTables.coveringEntry(allowedSchemas, allowedTables, table);
    }

    static List<String> normalizeList(List<String> raw) {
        return AllowedTables.normalize(raw);
    }
}
