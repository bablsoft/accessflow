package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;

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
 * Materializes a page of SQL++ result rows into the engine-neutral {@link SelectExecutionResult}.
 * Columns are the ordered union of top-level field names across the page; each row aligns its
 * values to that order, with absent fields as {@code null}. Non-object rows ({@code SELECT RAW …})
 * surface as a single {@code value} column. A {@code SELECT *} page — where every row is a
 * single-key object wrapped under the FROM alias — is unwrapped so the document fields become the
 * columns; without the unwrap, {@code collection.field} masking refs could never match. Nested
 * objects/arrays are preserved as {@link Map}/{@link List} so the persisted JSON stays valid and
 * the UI can both flatten to a table and reconstruct the documents for the JSON view. Restricted
 * columns and masking policies are applied per value via the shared {@link ColumnMasker},
 * <em>recursively by dot-path</em> (AF-658) so a mask on {@code profile.ssn} redacts the nested
 * leaf while the rest of {@code profile} stays visible; a whole-field {@code FULL} mask collapses
 * the subtree, and a list is a fan-out point rather than a path segment, so
 * {@code contacts.email} covers every element — identical to the MongoDB engine.
 */
class CouchbaseResultMapper {

    SelectExecutionResult materialize(List<Object> fetched, String unwrapKey, int maxRows,
                                      Duration duration, List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) {
        boolean truncated = fetched.size() > maxRows;
        var page = truncated ? fetched.subList(0, maxRows) : fetched;
        var rowsAsMaps = toRowMaps(page, unwrapKey);

        var fieldOrder = new LinkedHashSet<String>();
        for (var row : rowsAsMaps) {
            fieldOrder.addAll(row.keySet());
        }
        var fields = new ArrayList<>(fieldOrder);
        var matcher = new MaskMatcher(restrictedColumns, columnMasks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();

        var columns = new ArrayList<ResultColumn>(fields.size());
        for (var field : fields) {
            var mask = matcher.maskForPath(field);
            columns.add(new ResultColumn(field, Types.OTHER,
                    jsonTypeName(firstNonNull(rowsAsMaps, field)),
                    mask != null || matcher.hasRuleAtOrUnder(field)));
            if (mask != null && mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
        }

        var rows = new ArrayList<List<Object>>(rowsAsMaps.size());
        for (var rowMap : rowsAsMaps) {
            var row = new ArrayList<>(fields.size());
            for (var field : fields) {
                var raw = rowMap.get(field);
                // With no rules at all there is nothing to find, so skip the walk entirely rather
                // than deep-copying every document of a large result.
                row.add(matcher.isEmpty() ? raw
                        : maskValue(raw, field, matcher, appliedPolicyIds));
            }
            rows.add(row);
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
    }

    /**
     * Normalize a page of parsed rows to per-row field maps. Object rows keep their fields;
     * scalar/array rows ({@code SELECT RAW}) become {@code {value: …}}. When every row is a
     * single-key object keyed by {@code unwrapKey} (the {@code SELECT *} wrapper), the inner
     * document is unwrapped.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toRowMaps(List<Object> page, String unwrapKey) {
        boolean unwrap = unwrapKey != null && !page.isEmpty();
        if (unwrap) {
            for (var row : page) {
                if (!(row instanceof Map<?, ?> map) || map.size() != 1
                        || !(map.get(unwrapKey) instanceof Map<?, ?>)) {
                    unwrap = false;
                    break;
                }
            }
        }
        var out = new ArrayList<Map<String, Object>>(page.size());
        for (var row : page) {
            if (unwrap) {
                out.add((Map<String, Object>) ((Map<?, ?>) row).get(unwrapKey));
            } else if (row instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            } else {
                out.add(rawValueRow(row));
            }
        }
        return out;
    }

    /** A {@code SELECT RAW} scalar/array row; built by hand because the value may be null. */
    private static Map<String, Object> rawValueRow(Object row) {
        var map = new java.util.LinkedHashMap<String, Object>(1);
        map.put("value", row);
        return map;
    }

    private static Object maskValue(Object value, String path, MaskMatcher matcher,
                                    LinkedHashSet<UUID> appliedPolicyIds) {
        if (value == null) {
            return null;
        }
        var mask = matcher.maskForPath(path);
        if (mask != null) {
            if (mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
            if (mask.strategy() == MaskingStrategy.FULL) {
                // Redact the whole subtree rather than leaking its keys and shape.
                return ColumnMasker.FULL_MASK;
            }
            return ColumnMasker.apply(mask.strategy(), String.valueOf(value), mask.params());
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                out.put(key, maskValue(entry.getValue(), path + "." + key, matcher,
                        appliedPolicyIds));
            }
            return out;
        }
        // A list is a fan-out point, not a path segment: every element shares the parent path.
        if (value instanceof List<?> list) {
            var out = new ArrayList<>(list.size());
            for (var element : list) {
                out.add(maskValue(element, path, matcher, appliedPolicyIds));
            }
            return out;
        }
        return value;
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

    static String jsonTypeName(Object value) {
        return switch (value) {
            case null -> "null";
            case String ignored -> "string";
            case Boolean ignored -> "boolean";
            case Number ignored -> "number";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "array";
            default -> "string";
        };
    }

    /**
     * Resolves the masking that applies to a (possibly nested) field path, extending the SQL
     * {@code ColumnMaskResolver} precedence to dot-paths: the most qualified ref wins, so a
     * {@code collection.<path>} form beats an exact-path ref, which beats a bare last-segment
     * match; an unmatched bare restricted-columns entry defaults to {@link MaskingStrategy#FULL}.
     * {@link #hasRuleAtOrUnder(String)} lights up a top-level column's restricted flag when only a
     * nested field under it is masked. The dot-path walk mirrors the Elasticsearch mapper, so an
     * AF-447 tag on a nested dot-path — which the tag derivation always writes collection-qualified
     * — addresses the same leaf here (AF-658).
     */
    private static final class MaskMatcher {

        record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
        }

        private final List<DirectiveRef> directives = new ArrayList<>();
        private final List<String> restricted = new ArrayList<>();

        MaskMatcher(List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks) {
            if (columnMasks != null) {
                for (var directive : columnMasks) {
                    if (directive != null && directive.columnRef() != null
                            && !directive.columnRef().isBlank()) {
                        directives.add(new DirectiveRef(
                                directive.columnRef().trim().toLowerCase(Locale.ROOT), directive));
                    }
                }
            }
            if (restrictedColumns != null) {
                for (var entry : restrictedColumns) {
                    if (entry != null && !entry.isBlank()) {
                        restricted.add(entry.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        boolean isEmpty() {
            return directives.isEmpty() && restricted.isEmpty();
        }

        AppliedMask maskForPath(String path) {
            var column = path.toLowerCase(Locale.ROOT);
            ColumnMaskDirective best = null;
            int bestLevel = 0;
            for (var ref : directives) {
                int level = matchLevel(ref.ref(), column);
                if (level > bestLevel) {
                    bestLevel = level;
                    best = ref.directive();
                }
            }
            if (best != null) {
                return new AppliedMask(best.strategy(), best.params(), best.policyId());
            }
            for (var ref : restricted) {
                if (matchLevel(ref, column) > 0) {
                    return new AppliedMask(MaskingStrategy.FULL, Map.of(), null);
                }
            }
            return null;
        }

        boolean hasRuleAtOrUnder(String top) {
            var t = top.toLowerCase(Locale.ROOT);
            for (var ref : directives) {
                if (atOrUnder(ref.ref(), t)) {
                    return true;
                }
            }
            for (var ref : restricted) {
                if (atOrUnder(ref, t)) {
                    return true;
                }
            }
            return false;
        }

        /** 3 = qualified (….path), 2 = exact path, 1 = bare last-segment, 0 = no match. */
        private static int matchLevel(String ref, String path) {
            if (ref.endsWith("." + path)) {
                return 3;
            }
            if (ref.equals(path)) {
                return 2;
            }
            int dot = path.lastIndexOf('.');
            var last = dot >= 0 ? path.substring(dot + 1) : path;
            return ref.equals(last) ? 1 : 0;
        }

        private static boolean atOrUnder(String ref, String top) {
            return ref.equals(top)
                    || ref.startsWith(top + ".")
                    || ref.endsWith("." + top)
                    || ref.contains("." + top + ".");
        }

        private record DirectiveRef(String ref, ColumnMaskDirective directive) {
        }
    }
}
