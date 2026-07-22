package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.StandardSQLTypeName;

import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes a page of BigQuery rows into the engine-neutral {@link SelectExecutionResult}.
 * Columns come from the query {@code Schema}'s {@link FieldList} (name, GoogleSQL type name with
 * {@code ARRAY<…>} for REPEATED mode, best-effort {@code java.sql.Types} mapping);
 * {@link FieldValue}s are normalized to JSON-friendly Java values (STRING→String, INT64→Long,
 * FLOAT64→Double, NUMERIC/BIGNUMERIC→BigDecimal, BOOL→Boolean, TIMESTAMP→{@link java.time.Instant},
 * RECORD→ordered Map recursively, REPEATED→List; everything else keeps its string form).
 * Restricted columns and masking policies are applied per value via the shared
 * {@link ColumnMasker}; a mask ref is a <em>dot-path</em> into the row, so {@code user.email}
 * redacts only that nested RECORD leaf while siblings stay intact (a bare {@code email} masks the
 * whole column, recursing into nested maps/lists).
 */
class BigQueryResultMapper {

    SelectExecutionResult materialize(FieldList fields, List<FieldValueList> rows, int maxRows,
                                      Duration duration, List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) {
        boolean truncated = rows.size() > maxRows;
        var page = truncated ? rows.subList(0, maxRows) : rows;

        var planner = new MaskPlanner(restrictedColumns, columnMasks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();
        var columns = new ArrayList<ResultColumn>(fields.size());
        var plans = new ArrayList<ColumnPlan>(fields.size());
        for (var field : fields) {
            var plan = planner.planFor(field.getName());
            plans.add(plan);
            columns.add(new ResultColumn(field.getName(), sqlType(field), typeName(field),
                    plan != null));
            if (plan != null) {
                appliedPolicyIds.addAll(plan.policyIds());
            }
        }

        var out = new ArrayList<List<Object>>(page.size());
        for (var row : page) {
            var converted = new ArrayList<>(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                var raw = convert(fields.get(i), row.get(i));
                var plan = plans.get(i);
                converted.add(plan == null ? raw : plan.apply(raw));
            }
            out.add(converted);
        }
        return new SelectExecutionResult(columns, out, out.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
    }

    // ---- FieldValue → JSON-friendly value -------------------------------------------------------

    static Object convert(Field field, FieldValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.getAttribute() == FieldValue.Attribute.REPEATED) {
            var out = new ArrayList<>(value.getRepeatedValue().size());
            for (var element : value.getRepeatedValue()) {
                out.add(convertSingle(field, element));
            }
            return out;
        }
        return convertSingle(field, value);
    }

    private static Object convertSingle(Field field, FieldValue value) {
        if (value.isNull()) {
            return null;
        }
        if (standardType(field) == StandardSQLTypeName.STRUCT) {
            var subFields = field.getSubFields();
            var record = value.getRecordValue();
            var out = new LinkedHashMap<String, Object>();
            for (int i = 0; i < subFields.size(); i++) {
                out.put(subFields.get(i).getName(), convert(subFields.get(i), record.get(i)));
            }
            return out;
        }
        return switch (standardType(field)) {
            case BOOL -> value.getBooleanValue();
            case INT64 -> value.getLongValue();
            case FLOAT64 -> value.getDoubleValue();
            case NUMERIC, BIGNUMERIC -> value.getNumericValue();
            case TIMESTAMP -> value.getTimestampInstant();
            default -> value.getStringValue();
        };
    }

    private static StandardSQLTypeName standardType(Field field) {
        var legacy = field.getType();
        return legacy == null ? StandardSQLTypeName.STRING : legacy.getStandardType();
    }

    // ---- column metadata ------------------------------------------------------------------------

    static String typeName(Field field) {
        var base = standardType(field).name();
        return field.getMode() == Field.Mode.REPEATED ? "ARRAY<" + base + ">" : base;
    }

    static int sqlType(Field field) {
        if (field.getMode() == Field.Mode.REPEATED) {
            return Types.ARRAY;
        }
        if (LegacySQLTypeName.RECORD.equals(field.getType())) {
            return Types.STRUCT;
        }
        return switch (standardType(field)) {
            case BOOL -> Types.BOOLEAN;
            case INT64 -> Types.BIGINT;
            case FLOAT64 -> Types.DOUBLE;
            case NUMERIC, BIGNUMERIC -> Types.NUMERIC;
            case STRING -> Types.VARCHAR;
            case BYTES -> Types.VARBINARY;
            case TIMESTAMP, DATETIME -> Types.TIMESTAMP;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case STRUCT -> Types.STRUCT;
            default -> Types.OTHER;
        };
    }

    // ---- masking ------------------------------------------------------------------------------

    private static Object maskWhole(Object value, MaskPlanner.AppliedMask mask) {
        if (value == null) {
            return null;
        }
        if (mask.strategy() == MaskingStrategy.FULL) {
            // Fully redact the column — a whole nested map/list collapses to the mask token
            // rather than leaking its keys/shape.
            return ColumnMasker.FULL_MASK;
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), maskWhole(entry.getValue(), mask));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<>(list.size());
            for (var element : list) {
                out.add(maskWhole(element, mask));
            }
            return out;
        }
        return ColumnMasker.apply(mask.strategy(), String.valueOf(value), mask.params());
    }

    private static Object maskPath(Object value, List<String> path, MaskPlanner.AppliedMask mask) {
        if (path.isEmpty()) {
            return maskWhole(value, mask);
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                if (key.toLowerCase(Locale.ROOT).equals(path.get(0))) {
                    out.put(key, maskPath(entry.getValue(), path.subList(1, path.size()), mask));
                } else {
                    out.put(key, entry.getValue());
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<>(list.size());
            for (var element : list) {
                out.add(maskPath(element, path, mask));
            }
            return out;
        }
        return value;
    }

    /** The mask plan for a single top-level column: a whole-column mask or per-nested-path masks. */
    private record ColumnPlan(MaskPlanner.AppliedMask whole, List<PathMask> paths) {

        Object apply(Object value) {
            if (whole != null) {
                return maskWhole(value, whole);
            }
            var result = value;
            for (var pathMask : paths) {
                result = maskPath(result, pathMask.path(), pathMask.mask());
            }
            return result;
        }

        Set<UUID> policyIds() {
            var ids = new LinkedHashSet<UUID>();
            if (whole != null && whole.policyId() != null) {
                ids.add(whole.policyId());
            }
            for (var pathMask : paths) {
                if (pathMask.mask().policyId() != null) {
                    ids.add(pathMask.mask().policyId());
                }
            }
            return ids;
        }
    }

    private record PathMask(List<String> path, MaskPlanner.AppliedMask mask) {
    }

    /**
     * Resolves which mask (if any) applies to a top-level column, treating each directive /
     * restricted ref as a dot-path. An explicit directive on the bare column wins over a bare
     * restricted entry (which defaults to {@link MaskingStrategy#FULL}); a whole-column mask wins
     * over nested-path masks.
     */
    private static final class MaskPlanner {

        record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
        }

        private record Ref(List<String> segments, AppliedMask mask, boolean fromDirective) {
        }

        private final List<Ref> refs = new ArrayList<>();

        MaskPlanner(List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks) {
            if (columnMasks != null) {
                for (var directive : columnMasks) {
                    if (directive != null && directive.columnRef() != null
                            && !directive.columnRef().isBlank()) {
                        refs.add(new Ref(segments(directive.columnRef()),
                                new AppliedMask(directive.strategy(), directive.params(),
                                        directive.policyId()), true));
                    }
                }
            }
            if (restrictedColumns != null) {
                for (var entry : restrictedColumns) {
                    if (entry != null && !entry.isBlank()) {
                        refs.add(new Ref(segments(entry),
                                new AppliedMask(MaskingStrategy.FULL, Map.of(), null), false));
                    }
                }
            }
        }

        ColumnPlan planFor(String column) {
            var col = column.toLowerCase(Locale.ROOT);
            AppliedMask wholeDirective = null;
            AppliedMask wholeRestricted = null;
            var paths = new ArrayList<PathMask>();
            for (var ref : refs) {
                if (ref.segments().isEmpty() || !ref.segments().get(0).equals(col)) {
                    continue;
                }
                if (ref.segments().size() == 1) {
                    if (ref.fromDirective() && wholeDirective == null) {
                        wholeDirective = ref.mask();
                    } else if (!ref.fromDirective() && wholeRestricted == null) {
                        wholeRestricted = ref.mask();
                    }
                } else {
                    paths.add(new PathMask(ref.segments().subList(1, ref.segments().size()),
                            ref.mask()));
                }
            }
            var whole = wholeDirective != null ? wholeDirective : wholeRestricted;
            if (whole != null) {
                return new ColumnPlan(whole, List.of());
            }
            return paths.isEmpty() ? null : new ColumnPlan(null, List.copyOf(paths));
        }

        private static List<String> segments(String ref) {
            var lower = ref.trim().toLowerCase(Locale.ROOT);
            return List.of(lower.split("\\."));
        }
    }
}
