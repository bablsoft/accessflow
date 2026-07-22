package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes a JDBC {@link ResultSet} into the engine-neutral {@link SelectExecutionResult}.
 * Columns come from {@link java.sql.ResultSetMetaData} (label, JDBC type, type name); rows are
 * read up to {@code maxRows}, with a {@code maxRows + 1}-th row acting as the truncation sentinel
 * (the executor sets {@code setMaxRows(maxRows + 1)} driver-side). Restricted columns and masking
 * policies are applied per value via the shared {@link ColumnMasker}: a directive's
 * {@code columnRef} matches a result column when its last dot-segment equals the column label
 * case-insensitively (Snowflake folds unquoted identifiers to uppercase, so a policy written as
 * {@code users.email} matches the {@code EMAIL} label); a restricted column without an explicit
 * directive is masked {@link MaskingStrategy#FULL}. NULL cells pass through unmasked.
 */
class SnowflakeResultMapper {

    private record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
    }

    SelectExecutionResult materialize(ResultSet resultSet, int maxRows, Instant start, Clock clock,
                                      List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) throws SQLException {
        var metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        var columns = new ArrayList<ResultColumn>(columnCount);
        var masks = new ArrayList<AppliedMask>(columnCount);
        var maskPolicyIds = new LinkedHashSet<UUID>();
        for (int i = 1; i <= columnCount; i++) {
            var label = metaData.getColumnLabel(i);
            var mask = maskFor(label, restrictedColumns, columnMasks);
            masks.add(mask);
            columns.add(new ResultColumn(label, metaData.getColumnType(i),
                    metaData.getColumnTypeName(i), mask != null));
            if (mask != null && mask.policyId() != null) {
                maskPolicyIds.add(mask.policyId());
            }
        }
        var rows = new ArrayList<List<Object>>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() == maxRows) {
                truncated = true;
                break;
            }
            var row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(maskValue(resultSet.getObject(i), masks.get(i - 1)));
            }
            rows.add(row);
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated,
                Duration.between(start, clock.instant()), Set.copyOf(maskPolicyIds));
    }

    private static Object maskValue(Object value, AppliedMask mask) {
        if (mask == null || value == null) {
            return value;
        }
        return ColumnMasker.apply(mask.strategy(), String.valueOf(value), mask.params());
    }

    /**
     * The mask for a result column, if any: an explicit directive wins over a bare restricted
     * entry (which defaults to {@link MaskingStrategy#FULL} with no policy id).
     */
    private static AppliedMask maskFor(String label, List<String> restrictedColumns,
                                       List<ColumnMaskDirective> columnMasks) {
        if (columnMasks != null) {
            for (var directive : columnMasks) {
                if (directive != null && matches(directive.columnRef(), label)) {
                    return new AppliedMask(directive.strategy(), directive.params(),
                            directive.policyId());
                }
            }
        }
        if (restrictedColumns != null) {
            for (var entry : restrictedColumns) {
                if (matches(entry, label)) {
                    return new AppliedMask(MaskingStrategy.FULL, Map.of(), null);
                }
            }
        }
        return null;
    }

    private static boolean matches(String ref, String label) {
        if (ref == null || ref.isBlank() || label == null) {
            return false;
        }
        return lastSegment(ref).equals(label.toLowerCase(Locale.ROOT));
    }

    private static String lastSegment(String ref) {
        var normalized = ref.strip().toLowerCase(Locale.ROOT).replace("\"", "");
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }
}
