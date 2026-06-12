package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;

import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * Materializes a Redis reply into the engine-neutral {@link SelectExecutionResult}. Each command
 * family maps to a column/row shape (a single {@code value}; {@code key},{@code value} pairs; hash
 * field-name columns; or a payload column with N rows). Restricted columns and masking policies are
 * applied per value via the shared {@link ColumnMasker}, with the same {@code prefix.field} → bare
 * {@code field} precedence the SQL and MongoDB engines use, so a masking policy on a key-prefix
 * "table" (e.g. {@code session.token}) redacts the matching column.
 */
class RedisResultMapper {

    static final String VALUE_COLUMN = "value";
    static final String KEY_COLUMN = "key";

    /** A single scalar / string / value-returning reply: one {@code value} column, one row. */
    SelectExecutionResult singleValue(Object value, Duration duration, List<String> restricted,
                                      List<ColumnMaskDirective> masks) {
        return materialize(List.of(VALUE_COLUMN), List.of(singleRow(value)), false, duration,
                restricted, masks);
    }

    /** {@code MGET}: {@code key},{@code value} columns, one row per requested key. */
    SelectExecutionResult keyValues(List<String> keys, List<String> values, Duration duration,
                                    List<String> restricted, List<ColumnMaskDirective> masks) {
        var rows = new ArrayList<List<Object>>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            rows.add(row(keys.get(i), i < values.size() ? values.get(i) : null));
        }
        return materialize(List.of(KEY_COLUMN, VALUE_COLUMN), rows, false, duration, restricted, masks);
    }

    /** {@code HGETALL}: hash field names as columns, a single row of values (empty hash → no rows). */
    SelectExecutionResult hashMap(Map<String, String> hash, Duration duration,
                                  List<String> restricted, List<ColumnMaskDirective> masks) {
        if (hash.isEmpty()) {
            return materialize(List.of(), List.of(), false, duration, restricted, masks);
        }
        var fields = new ArrayList<>(hash.keySet());
        var values = new ArrayList<Object>(fields.size());
        for (var field : fields) {
            values.add(hash.get(field));
        }
        return materialize(fields, List.of(values), false, duration, restricted, masks);
    }

    /** {@code HGET}/{@code HMGET}: the requested field name(s) as columns, one row. */
    SelectExecutionResult hashFields(List<String> fields, List<String> values, Duration duration,
                                     List<String> restricted, List<ColumnMaskDirective> masks) {
        var row = new ArrayList<Object>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            row.add(i < values.size() ? values.get(i) : null);
        }
        return materialize(List.copyOf(fields), List.of(row), false, duration, restricted, masks);
    }

    /** A collection reply (list/set/zset/hkeys/hvals): a single payload column, N rows, capped. */
    SelectExecutionResult collection(List<String> values, int maxRows, Duration duration,
                                     List<String> restricted, List<ColumnMaskDirective> masks) {
        return column(VALUE_COLUMN, values, false, maxRows, duration, restricted, masks);
    }

    /** {@code KEYS}/{@code SCAN}: a {@code key} column, N rows; {@code moreToScan} forces truncated. */
    SelectExecutionResult keys(List<String> keys, boolean moreToScan, int maxRows, Duration duration,
                               List<String> restricted, List<ColumnMaskDirective> masks) {
        return column(KEY_COLUMN, keys, moreToScan, maxRows, duration, restricted, masks);
    }

    /** A custom column/row shape (e.g. {@code ZRANGE … WITHSCORES} → {@code member},{@code score}). */
    SelectExecutionResult rows(List<String> columnNames, List<List<Object>> rows, boolean truncated,
                               Duration duration, List<String> restricted,
                               List<ColumnMaskDirective> masks) {
        return materialize(columnNames, rows, truncated, duration, restricted, masks);
    }

    private SelectExecutionResult column(String name, List<String> values, boolean forceTruncated,
                                         int maxRows, Duration duration, List<String> restricted,
                                         List<ColumnMaskDirective> masks) {
        boolean truncated = forceTruncated || values.size() > maxRows;
        var capped = values.size() > maxRows ? values.subList(0, maxRows) : values;
        var rows = new ArrayList<List<Object>>(capped.size());
        for (var value : capped) {
            rows.add(singleRow(value));
        }
        return materialize(List.of(name), rows, truncated, duration, restricted, masks);
    }

    private SelectExecutionResult materialize(List<String> columnNames, List<List<Object>> rows,
                                              boolean truncated, Duration duration,
                                              List<String> restricted,
                                              List<ColumnMaskDirective> masks) {
        var matcher = new MaskMatcher(restricted, masks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();
        var columnMasks = new ArrayList<MaskMatcher.AppliedMask>(columnNames.size());
        var columns = new ArrayList<ResultColumn>(columnNames.size());
        for (int c = 0; c < columnNames.size(); c++) {
            var name = columnNames.get(c);
            var mask = matcher.maskFor(name);
            columnMasks.add(mask);
            columns.add(new ResultColumn(name, jdbcType(sample(rows, c)), typeName(sample(rows, c)),
                    mask != null));
            if (mask != null && mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
        }
        var maskedRows = new ArrayList<List<Object>>(rows.size());
        for (var row : rows) {
            var out = new ArrayList<>(row.size());
            for (int c = 0; c < row.size(); c++) {
                var mask = c < columnMasks.size() ? columnMasks.get(c) : null;
                out.add(mask == null ? row.get(c) : maskValue(row.get(c), mask));
            }
            maskedRows.add(out);
        }
        return new SelectExecutionResult(columns, maskedRows, maskedRows.size(), truncated, duration,
                appliedPolicyIds);
    }

    private static Object maskValue(Object raw, MaskMatcher.AppliedMask mask) {
        if (raw == null) {
            return null;
        }
        if (mask.strategy() == MaskingStrategy.FULL) {
            return ColumnMasker.FULL_MASK;
        }
        return ColumnMasker.apply(mask.strategy(), String.valueOf(raw), mask.params());
    }

    private static List<Object> singleRow(Object value) {
        var row = new ArrayList<Object>(1);
        row.add(value);
        return row;
    }

    private static List<Object> row(Object a, Object b) {
        var row = new ArrayList<Object>(2);
        row.add(a);
        row.add(b);
        return row;
    }

    private static Object sample(List<List<Object>> rows, int column) {
        for (var row : rows) {
            if (column < row.size() && row.get(column) != null) {
                return row.get(column);
            }
        }
        return null;
    }

    private static int jdbcType(Object value) {
        return switch (value) {
            case Long ignored -> Types.BIGINT;
            case Integer ignored -> Types.INTEGER;
            case Double ignored -> Types.DOUBLE;
            case Boolean ignored -> Types.BOOLEAN;
            default -> Types.VARCHAR;
        };
    }

    private static String typeName(Object value) {
        return switch (value) {
            case null -> "string";
            case Long ignored -> "integer";
            case Integer ignored -> "integer";
            case Double ignored -> "double";
            case Boolean ignored -> "boolean";
            default -> "string";
        };
    }

    /**
     * Resolves the masking that applies to a column, mirroring the SQL/MongoDB precedence
     * ({@code prefix.column} → bare {@code column}). Explicit mask directives win over a bare
     * restricted-columns entry, which defaults to {@link MaskingStrategy#FULL}.
     */
    private static final class MaskMatcher {

        record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
        }

        private final List<DirectiveRef> directives = new ArrayList<>();
        private final List<RefKeys> restricted = new ArrayList<>();

        MaskMatcher(List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks) {
            if (columnMasks != null) {
                for (var directive : columnMasks) {
                    if (directive != null && directive.columnRef() != null
                            && !directive.columnRef().isBlank()) {
                        directives.add(new DirectiveRef(RefKeys.parse(directive.columnRef()), directive));
                    }
                }
            }
            if (restrictedColumns != null) {
                for (var entry : restrictedColumns) {
                    if (entry != null && !entry.isBlank()) {
                        restricted.add(RefKeys.parse(entry));
                    }
                }
            }
        }

        AppliedMask maskFor(String columnName) {
            var column = columnName.toLowerCase(Locale.ROOT);
            ColumnMaskDirective best = null;
            int bestLevel = 0;
            for (var ref : directives) {
                int level = ref.keys().matchLevel(column);
                if (level > bestLevel) {
                    bestLevel = level;
                    best = ref.directive();
                }
            }
            if (best != null) {
                return new AppliedMask(best.strategy(), best.params(), best.policyId());
            }
            for (var ref : restricted) {
                if (ref.matchLevel(column) > 0) {
                    return new AppliedMask(MaskingStrategy.FULL, Map.of(), null);
                }
            }
            return null;
        }

        private record DirectiveRef(RefKeys keys, ColumnMaskDirective directive) {
        }

        private record RefKeys(String table, String bare) {

            static RefKeys parse(String entry) {
                var lower = entry.trim().toLowerCase(Locale.ROOT);
                var parts = lower.split("\\.");
                return parts.length == 1
                        ? new RefKeys(null, parts[0])
                        : new RefKeys(parts[parts.length - 2] + "." + parts[parts.length - 1],
                                parts[parts.length - 1]);
            }

            /** 2 = prefix.column, 1 = bare column, 0 = no match. */
            int matchLevel(String column) {
                if (table != null && table.endsWith("." + column)) {
                    return 2;
                }
                return bare.equals(column) ? 1 : 0;
            }
        }
    }
}
