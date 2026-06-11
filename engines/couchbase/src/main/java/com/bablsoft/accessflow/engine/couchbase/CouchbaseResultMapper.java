package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;

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
 * Materializes a page of SQL++ result rows into the engine-neutral {@link SelectExecutionResult}.
 * Columns are the ordered union of top-level field names across the page; each row aligns its
 * values to that order, with absent fields as {@code null}. Non-object rows ({@code SELECT RAW …})
 * surface as a single {@code value} column. A {@code SELECT *} page — where every row is a
 * single-key object wrapped under the FROM alias — is unwrapped so the document fields become the
 * columns; without the unwrap, {@code collection.field} masking refs could never match. Nested
 * objects/arrays are preserved as {@link Map}/{@link List} so the persisted JSON stays valid and
 * the UI can both flatten to a table and reconstruct the documents for the JSON view. Restricted
 * columns and masking policies are applied per value via the shared {@link ColumnMasker},
 * identical to the SQL and MongoDB engines.
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
            var mask = matcher.maskFor(field);
            columns.add(new ResultColumn(field, Types.OTHER,
                    jsonTypeName(firstNonNull(rowsAsMaps, field)), mask != null));
            if (mask != null && mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
        }

        var rows = new ArrayList<List<Object>>(rowsAsMaps.size());
        for (var rowMap : rowsAsMaps) {
            var row = new ArrayList<>(fields.size());
            for (var field : fields) {
                var raw = rowMap.get(field);
                var mask = matcher.maskFor(field);
                row.add(mask == null ? raw : maskValue(raw, mask));
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

    private static Object maskValue(Object raw, MaskMatcher.AppliedMask mask) {
        if (raw == null) {
            return null;
        }
        if (mask.strategy() == MaskingStrategy.FULL) {
            return ColumnMasker.FULL_MASK;
        }
        return ColumnMasker.apply(mask.strategy(), String.valueOf(raw), mask.params());
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
     * Resolves the masking that applies to a top-level field, mirroring the SQL
     * {@code ColumnMaskResolver} precedence ({@code collection.field} → bare {@code field}; the
     * bucket/scope-qualified levels are unused here). Explicit mask directives win over a bare
     * restricted-columns entry, which defaults to {@link MaskingStrategy#FULL}.
     */
    private static final class MaskMatcher {

        record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
        }

        private final List<DirectiveRef> directives;
        private final List<RefKeys> restricted;

        MaskMatcher(List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks) {
            this.directives = new ArrayList<>();
            this.restricted = new ArrayList<>();
            if (columnMasks != null) {
                for (var directive : columnMasks) {
                    if (directive != null && directive.columnRef() != null
                            && !directive.columnRef().isBlank()) {
                        directives.add(new DirectiveRef(RefKeys.parse(directive.columnRef()),
                                directive));
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

        AppliedMask maskFor(String field) {
            var column = field.toLowerCase(Locale.ROOT);
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

            /** 2 = collection.field, 1 = bare field, 0 = no match (no scope level here). */
            int matchLevel(String column) {
                if (table != null && table.endsWith("." + column)) {
                    return 2;
                }
                return bare.equals(column) ? 1 : 0;
            }
        }
    }
}
