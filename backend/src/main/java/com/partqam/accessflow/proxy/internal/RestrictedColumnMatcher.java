package com.partqam.accessflow.proxy.internal;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps restricted-column entries (each formatted as {@code schema.table.column}) onto a JDBC result
 * set's column indices.
 *
 * <p>Match precedence per result column:
 * <ol>
 *   <li>Exact {@code schema.table.column} match (case-insensitive) when both schema and table are
 *       provided by the JDBC driver and present in the restricted set.</li>
 *   <li>{@code table.column} match when only the table is available.</li>
 *   <li>Bare {@code column} match — falls back to the last segment of restricted entries. Errs
 *       toward over-masking when the driver omits table metadata (computed expressions, aliased
 *       output, some CTEs).</li>
 * </ol>
 */
final class RestrictedColumnMatcher {

    private final boolean[] mask;

    private RestrictedColumnMatcher(boolean[] mask) {
        this.mask = mask;
    }

    static RestrictedColumnMatcher build(ResultSetMetaData metadata, List<String> restrictedColumns)
            throws SQLException {
        int columnCount = metadata.getColumnCount();
        var mask = new boolean[columnCount];
        if (restrictedColumns == null || restrictedColumns.isEmpty()) {
            return new RestrictedColumnMatcher(mask);
        }
        var fullyQualified = new HashSet<String>();
        var tableQualified = new HashSet<String>();
        var bare = new HashSet<String>();
        for (var entry : restrictedColumns) {
            if (entry == null) {
                continue;
            }
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var lower = trimmed.toLowerCase(Locale.ROOT);
            var parts = lower.split("\\.");
            switch (parts.length) {
                case 1 -> bare.add(parts[0]);
                case 2 -> {
                    tableQualified.add(parts[0] + "." + parts[1]);
                    bare.add(parts[1]);
                }
                default -> {
                    fullyQualified.add(parts[parts.length - 3] + "." + parts[parts.length - 2]
                            + "." + parts[parts.length - 1]);
                    tableQualified.add(parts[parts.length - 2] + "." + parts[parts.length - 1]);
                    bare.add(parts[parts.length - 1]);
                }
            }
        }
        for (int i = 1; i <= columnCount; i++) {
            var schema = safeLower(metadata.getSchemaName(i));
            var table = safeLower(metadata.getTableName(i));
            var column = safeLower(columnLabel(metadata, i));
            if (column == null) {
                continue;
            }
            if (schema != null && table != null
                    && fullyQualified.contains(schema + "." + table + "." + column)) {
                mask[i - 1] = true;
                continue;
            }
            if (table != null && tableQualified.contains(table + "." + column)) {
                mask[i - 1] = true;
                continue;
            }
            if (bare.contains(column)) {
                mask[i - 1] = true;
            }
        }
        return new RestrictedColumnMatcher(mask);
    }

    boolean isRestricted(int oneBasedIndex) {
        return mask[oneBasedIndex - 1];
    }

    Set<Integer> restrictedIndices() {
        var indices = new HashSet<Integer>();
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                indices.add(i + 1);
            }
        }
        return indices;
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
