package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.ColumnRefKeys;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maps each JDBC result-set column index onto the masking that applies to it, combining two
 * sources with the same match precedence used by the legacy restricted-columns feature:
 *
 * <ol>
 *   <li>Exact {@code schema.table.column} (case-insensitive) when the driver supplies both.</li>
 *   <li>{@code table.column} when only the table is available.</li>
 *   <li>Bare {@code column} — over-masks when the driver omits table metadata (computed
 *       expressions, aliased output, some CTEs).</li>
 * </ol>
 *
 * <p>Explicit {@link ColumnMaskDirective}s (from masking policies) take precedence over a bare
 * {@code restricted_columns} entry, which defaults to {@link MaskingStrategy#FULL} ({@code "***"})
 * — preserving today's behaviour for columns with no policy. Among multiple matching directives the
 * most specific level wins; ties resolve to declaration order.
 */
final class ColumnMaskResolver {

    record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
    }

    private final AppliedMask[] masks;
    private final Set<UUID> appliedPolicyIds;

    private ColumnMaskResolver(AppliedMask[] masks, Set<UUID> appliedPolicyIds) {
        this.masks = masks;
        this.appliedPolicyIds = appliedPolicyIds;
    }

    static ColumnMaskResolver build(ResultSetMetaData metadata, List<String> restrictedColumns,
                                    List<ColumnMaskDirective> columnMasks) throws SQLException {
        int columnCount = metadata.getColumnCount();
        var masks = new AppliedMask[columnCount];
        var appliedPolicyIds = new LinkedHashSet<UUID>();

        var directiveRefs = parseDirectives(columnMasks);
        var restrictedRefs = parseRestricted(restrictedColumns);

        for (int i = 1; i <= columnCount; i++) {
            var schema = safeLower(metadata.getSchemaName(i));
            var table = safeLower(metadata.getTableName(i));
            var column = safeLower(columnLabel(metadata, i));
            if (column == null) {
                continue;
            }
            var directive = bestDirective(directiveRefs, schema, table, column);
            if (directive != null) {
                masks[i - 1] = new AppliedMask(directive.strategy(), directive.params(),
                        directive.policyId());
                if (directive.policyId() != null) {
                    appliedPolicyIds.add(directive.policyId());
                }
                continue;
            }
            if (matchesAny(restrictedRefs, schema, table, column)) {
                masks[i - 1] = new AppliedMask(MaskingStrategy.FULL, Map.of(), null);
            }
        }
        return new ColumnMaskResolver(masks, Set.copyOf(appliedPolicyIds));
    }

    boolean isMasked(int oneBasedIndex) {
        return masks[oneBasedIndex - 1] != null;
    }

    AppliedMask maskFor(int oneBasedIndex) {
        return masks[oneBasedIndex - 1];
    }

    Set<UUID> appliedPolicyIds() {
        return appliedPolicyIds;
    }

    private static ColumnMaskDirective bestDirective(List<DirectiveRef> directiveRefs, String schema,
                                                     String table, String column) {
        ColumnMaskDirective best = null;
        int bestLevel = 0;
        for (var ref : directiveRefs) {
            int level = ref.keys().matchLevel(schema, table, column);
            if (level > bestLevel) {
                bestLevel = level;
                best = ref.directive();
            }
        }
        return best;
    }

    private static boolean matchesAny(List<ColumnRefKeys> refs, String schema, String table,
                                      String column) {
        for (var ref : refs) {
            if (ref.matchLevel(schema, table, column) > 0) {
                return true;
            }
        }
        return false;
    }

    private static List<DirectiveRef> parseDirectives(List<ColumnMaskDirective> directives) {
        if (directives == null || directives.isEmpty()) {
            return List.of();
        }
        var parsed = new java.util.ArrayList<DirectiveRef>(directives.size());
        for (var directive : directives) {
            if (directive == null || directive.columnRef() == null
                    || directive.columnRef().isBlank()) {
                continue;
            }
            parsed.add(new DirectiveRef(ColumnRefKeys.parse(directive.columnRef()), directive));
        }
        return parsed;
    }

    private static List<ColumnRefKeys> parseRestricted(List<String> restrictedColumns) {
        if (restrictedColumns == null || restrictedColumns.isEmpty()) {
            return List.of();
        }
        var parsed = new java.util.ArrayList<ColumnRefKeys>(restrictedColumns.size());
        for (var entry : restrictedColumns) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            parsed.add(ColumnRefKeys.parse(entry));
        }
        return parsed;
    }

    private record DirectiveRef(ColumnRefKeys keys, ColumnMaskDirective directive) {
    }

    private static String safeLower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String columnLabel(ResultSetMetaData metadata, int index) throws SQLException {
        var label = metadata.getColumnLabel(index);
        if (label == null || label.isBlank()) {
            label = metadata.getColumnName(index);
        }
        return label;
    }
}
