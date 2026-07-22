package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes an inline Statement Execution API result into the engine-neutral
 * {@link SelectExecutionResult}. Columns come from the manifest schema (name + Databricks
 * {@code type_name}, with a best-effort {@code java.sql.Types} mapping); the {@code JSON_ARRAY}
 * wire format delivers every value as string-or-null, so values are converted best-effort by the
 * column's type name ({@code BOOLEAN} → {@link Boolean}, integral types → {@link Long},
 * fractional/decimal types → {@link BigDecimal}; anything unconvertible stays the raw string).
 * Truncation is flagged when the page exceeds {@code maxRows} (the {@code maxRows + 1} sentinel
 * row is dropped) or when the manifest itself reports the server-side {@code row_limit} cut.
 * Masking is applied post-fetch with flat, case-insensitive column-name matching (a qualified
 * directive ref matches by its last segment): a restricted column without a directive collapses to
 * {@link MaskingStrategy#FULL}, directives apply through the shared {@link ColumnMasker}, and
 * masked columns are flagged on their {@link ResultColumn}. Also extracts the
 * {@code num_affected_rows} count Databricks returns as a one-row result for DML statements.
 */
class DatabricksResultMapper {

    private static final String AFFECTED_ROWS_COLUMN = "num_affected_rows";

    SelectExecutionResult materialize(DatabricksStatementClient.StatementResult result,
                                      int maxRows, Duration duration,
                                      List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) {
        boolean truncated = result.rows().size() > maxRows || result.truncated();
        var page = result.rows().size() > maxRows ? result.rows().subList(0, maxRows)
                : result.rows();

        var appliedPolicyIds = new LinkedHashSet<UUID>();
        var columns = new ArrayList<ResultColumn>(result.columns().size());
        var masks = new ArrayList<AppliedMask>(result.columns().size());
        for (var column : result.columns()) {
            var mask = maskFor(column.name(), restrictedColumns, columnMasks);
            masks.add(mask);
            columns.add(new ResultColumn(column.name(), sqlType(column.typeName()),
                    column.typeName(), mask != null));
            if (mask != null && mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
        }

        var rows = new ArrayList<List<Object>>(page.size());
        for (var raw : page) {
            var out = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                var value = i < raw.size() ? raw.get(i) : null;
                var mask = masks.get(i);
                if (mask != null) {
                    out.add(ColumnMasker.apply(mask.strategy(), value, mask.params()));
                } else {
                    out.add(convert(value, i < result.columns().size()
                            ? result.columns().get(i).typeName() : null));
                }
            }
            rows.add(out);
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
    }

    /** The {@code num_affected_rows} value of a DML result, or 0 when the shape is absent. */
    long affectedRows(DatabricksStatementClient.StatementResult result) {
        for (int i = 0; i < result.columns().size(); i++) {
            if (AFFECTED_ROWS_COLUMN.equalsIgnoreCase(result.columns().get(i).name())) {
                for (var row : result.rows()) {
                    var value = i < row.size() ? row.get(i) : null;
                    if (value != null) {
                        try {
                            return Long.parseLong(value.strip());
                        } catch (NumberFormatException e) {
                            return 0;
                        }
                    }
                }
            }
        }
        return 0;
    }

    // ---- value conversion ------------------------------------------------------------------------

    /** Best-effort typed value from the JSON_ARRAY string form; falls back to the raw string. */
    static Object convert(String raw, String typeName) {
        if (raw == null) {
            return null;
        }
        return switch (baseType(typeName)) {
            case "BOOLEAN" -> "true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)
                    ? Boolean.valueOf(raw) : raw;
            case "TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT", "LONG" -> parseLong(raw);
            case "FLOAT", "REAL", "DOUBLE", "DECIMAL", "NUMERIC" -> parseDecimal(raw);
            default -> raw;
        };
    }

    private static Object parseLong(String raw) {
        try {
            return Long.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static Object parseDecimal(String raw) {
        try {
            return new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // ---- type mapping ------------------------------------------------------------------------------

    /** Best-effort {@code java.sql.Types} constant for a Databricks manifest type name. */
    static int sqlType(String typeName) {
        return switch (baseType(typeName)) {
            case "BOOLEAN" -> Types.BOOLEAN;
            case "TINYINT" -> Types.TINYINT;
            case "SMALLINT" -> Types.SMALLINT;
            case "INT", "INTEGER" -> Types.INTEGER;
            case "BIGINT", "LONG" -> Types.BIGINT;
            case "FLOAT", "REAL" -> Types.FLOAT;
            case "DOUBLE" -> Types.DOUBLE;
            case "DECIMAL", "NUMERIC" -> Types.DECIMAL;
            case "STRING", "CHAR", "VARCHAR" -> Types.VARCHAR;
            case "DATE" -> Types.DATE;
            case "TIMESTAMP", "TIMESTAMP_NTZ" -> Types.TIMESTAMP;
            case "BINARY" -> Types.BINARY;
            case "INTERVAL" -> Types.OTHER;
            case "ARRAY", "MAP", "STRUCT", "VARIANT" -> Types.OTHER;
            case "NULL", "VOID" -> Types.NULL;
            default -> Types.OTHER;
        };
    }

    /** {@code DECIMAL(10,2)} → {@code DECIMAL}; {@code ARRAY<INT>} → {@code ARRAY}. */
    private static String baseType(String typeName) {
        if (typeName == null) {
            return "";
        }
        var upper = typeName.strip().toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(' || c == '<' || c == ' ') {
                return upper.substring(0, i);
            }
        }
        return upper;
    }

    // ---- masking ------------------------------------------------------------------------------------

    private record AppliedMask(MaskingStrategy strategy, Map<String, String> params,
                               UUID policyId) {
    }

    /**
     * Flat, case-insensitive resolution: an explicit directive (matched by its ref's last dot
     * segment) wins over a bare restricted-column entry, which defaults to FULL.
     */
    private static AppliedMask maskFor(String column, List<String> restrictedColumns,
                                       List<ColumnMaskDirective> columnMasks) {
        if (columnMasks != null) {
            for (var directive : columnMasks) {
                if (directive != null && matches(directive.columnRef(), column)) {
                    return new AppliedMask(directive.strategy(), directive.params(),
                            directive.policyId());
                }
            }
        }
        if (restrictedColumns != null) {
            for (var restricted : restrictedColumns) {
                if (matches(restricted, column)) {
                    return new AppliedMask(MaskingStrategy.FULL, Map.of(), null);
                }
            }
        }
        return null;
    }

    private static boolean matches(String ref, String column) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        var normalized = ref.strip().toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        var last = dot >= 0 ? normalized.substring(dot + 1) : normalized;
        return last.equals(column.toLowerCase(Locale.ROOT));
    }
}
