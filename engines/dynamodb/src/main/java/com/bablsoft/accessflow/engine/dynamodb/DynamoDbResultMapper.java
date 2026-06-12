package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes a page of DynamoDB items into the engine-neutral {@link SelectExecutionResult}.
 * Columns are the ordered union of top-level attribute names across the page (so heterogeneous
 * items still render as a table); each row aligns to that order, absent attributes as {@code null}.
 * {@link AttributeValue}s are normalized to JSON-friendly Java values (S→String, N→BigDecimal,
 * BOOL→Boolean, M→Map, L→List, B→{@code base64:…}, sets→List). Restricted columns and masking
 * policies are applied per value via the shared {@link ColumnMasker}; a mask ref is a <em>dot-path</em>
 * into the item, so {@code user.email} redacts only that nested leaf while siblings stay intact (a
 * bare {@code email} masks the whole attribute, recursing into nested maps/lists).
 */
class DynamoDbResultMapper {

    private static final String BASE64_PREFIX = "base64:";

    SelectExecutionResult materialize(List<Map<String, AttributeValue>> items, int maxRows,
                                      Duration duration, List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) {
        boolean truncated = items.size() > maxRows;
        var page = truncated ? items.subList(0, maxRows) : items;

        var converted = new ArrayList<Map<String, Object>>(page.size());
        var fieldOrder = new LinkedHashSet<String>();
        for (var item : page) {
            var row = new LinkedHashMap<String, Object>();
            for (var entry : item.entrySet()) {
                row.put(entry.getKey(), convert(entry.getValue()));
            }
            converted.add(row);
            fieldOrder.addAll(item.keySet());
        }
        var fields = new ArrayList<>(fieldOrder);
        var planner = new MaskPlanner(restrictedColumns, columnMasks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();

        var columns = new ArrayList<ResultColumn>(fields.size());
        var plans = new ArrayList<ColumnPlan>(fields.size());
        for (var field : fields) {
            var plan = planner.planFor(field);
            plans.add(plan);
            columns.add(new ResultColumn(field, Types.OTHER, typeName(firstNonNull(converted, field)),
                    plan != null));
            if (plan != null) {
                appliedPolicyIds.addAll(plan.policyIds());
            }
        }

        var rows = new ArrayList<List<Object>>(converted.size());
        for (var row : converted) {
            var out = new ArrayList<>(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                var raw = row.get(fields.get(i));
                var plan = plans.get(i);
                out.add(plan == null ? raw : plan.apply(raw));
            }
            rows.add(out);
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
    }

    // ---- AttributeValue → JSON-friendly value -------------------------------------------------

    static Object convert(AttributeValue value) {
        if (value == null) {
            return null;
        }
        return switch (value.type()) {
            case S -> value.s();
            case N -> number(value.n());
            case BOOL -> value.bool();
            case NUL -> null;
            case M -> convertMap(value.m());
            case L -> value.l().stream().map(DynamoDbResultMapper::convert).toList();
            case B -> base64(value.b());
            case SS -> List.copyOf(value.ss());
            case NS -> value.ns().stream().map(DynamoDbResultMapper::number).toList();
            case BS -> value.bs().stream().map(DynamoDbResultMapper::base64).toList();
            case UNKNOWN_TO_SDK_VERSION -> null;
        };
    }

    private static Map<String, Object> convertMap(Map<String, AttributeValue> map) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            out.put(entry.getKey(), convert(entry.getValue()));
        }
        return out;
    }

    private static Object number(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return raw;
        }
    }

    private static String base64(SdkBytes bytes) {
        return BASE64_PREFIX + Base64.getEncoder().encodeToString(bytes.asByteArray());
    }

    private static Object firstNonNull(List<Map<String, Object>> rows, String field) {
        for (var row : rows) {
            var value = row.get(field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String typeName(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> s.startsWith(BASE64_PREFIX) ? "binary" : "string";
            case BigDecimal ignored -> "number";
            case Number ignored -> "number";
            case Boolean ignored -> "bool";
            case Map<?, ?> ignored -> "map";
            case List<?> ignored -> "list";
            default -> "string";
        };
    }

    // ---- masking ------------------------------------------------------------------------------

    private static Object maskWhole(Object value, MaskPlanner.AppliedMask mask) {
        if (value == null) {
            return null;
        }
        if (mask.strategy() == MaskingStrategy.FULL) {
            // Fully redact the attribute — a whole nested map/list collapses to the mask token
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

    /** The mask plan for a single top-level column: a whole-attribute mask or per-nested-path masks. */
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
     * restricted entry (which defaults to {@link MaskingStrategy#FULL}); a whole-attribute mask wins
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
