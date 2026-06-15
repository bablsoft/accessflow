package com.bablsoft.accessflow.engine.neo4j;

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
 * Materializes a page of Cypher result records into the engine-neutral {@link SelectExecutionResult}.
 * Columns are the {@code RETURN} keys; values are the JSON-friendly forms produced by
 * {@link Neo4jValueConverter} (nodes/relationships flattened to maps carrying {@code _labels} /
 * {@code _type} plus their properties, paths to lists, scalars verbatim). Restricted columns and
 * masking policies are applied per value via the shared {@link ColumnMasker}, <em>label-aware and
 * recursive</em>: a {@code Label.property} directive redacts the {@code property} of any returned
 * node/relationship whose labels include {@code Label} (however it is aliased), and a bare
 * {@code property} directive redacts that property anywhere it appears (nested maps/lists included)
 * and any top-level scalar column of that name — so a masked property is never leaked through a
 * graph projection.
 */
class Neo4jResultMapper {

    static final String LABELS_KEY = "_labels";
    static final String TYPE_KEY = "_type";

    SelectExecutionResult materialize(List<String> columns, List<List<Object>> fetched, int maxRows,
                                      Duration duration, List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) {
        boolean truncated = fetched.size() > maxRows;
        var page = truncated ? fetched.subList(0, maxRows) : fetched;
        var planner = new MaskPlanner(restrictedColumns, columnMasks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();
        var restricted = new boolean[columns.size()];

        var rows = new ArrayList<List<Object>>(page.size());
        for (var fetchedRow : page) {
            var out = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                var raw = i < fetchedRow.size() ? fetchedRow.get(i) : null;
                var sink = new MaskSink();
                out.add(planner.maskColumn(columns.get(i), raw, sink));
                if (!sink.policyIds.isEmpty() || sink.applied) {
                    restricted[i] = true;
                    appliedPolicyIds.addAll(sink.policyIds);
                }
            }
            rows.add(out);
        }

        var resultColumns = new ArrayList<ResultColumn>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            resultColumns.add(new ResultColumn(columns.get(i), Types.OTHER,
                    typeName(firstNonNull(rows, i)), restricted[i]));
        }
        return new SelectExecutionResult(resultColumns, rows, rows.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
    }

    private static Object firstNonNull(List<List<Object>> rows, int index) {
        for (var row : rows) {
            var value = index < row.size() ? row.get(index) : null;
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String typeName(Object value) {
        return switch (value) {
            case null -> "null";
            case String ignored -> "string";
            case Boolean ignored -> "bool";
            case Number ignored -> "number";
            case Map<?, ?> map -> map.containsKey(LABELS_KEY) ? "node"
                    : map.containsKey(TYPE_KEY) ? "relationship" : "map";
            case List<?> ignored -> "list";
            default -> "string";
        };
    }

    // ---- masking ------------------------------------------------------------------------------

    /** Collects which policy ids fired (and whether a restricted-columns mask, with no policy, fired). */
    private static final class MaskSink {
        private final Set<UUID> policyIds = new LinkedHashSet<>();
        private boolean applied;

        void record(UUID policyId) {
            applied = true;
            if (policyId != null) {
                policyIds.add(policyId);
            }
        }
    }

    private record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
    }

    /**
     * Resolves the masks that apply to a returned value. Directives win over a bare restricted
     * entry (which defaults to {@link MaskingStrategy#FULL}); a label-qualified directive
     * ({@code Label.prop}) wins over a bare ({@code prop}) one.
     */
    private static final class MaskPlanner {

        /** Label-qualified masks: lowercased label -> (lowercased property -> mask). */
        private final Map<String, Map<String, AppliedMask>> labelMasks = new LinkedHashMap<>();
        /** Bare property masks: lowercased property -> mask. */
        private final Map<String, AppliedMask> bareMasks = new LinkedHashMap<>();

        MaskPlanner(List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks) {
            if (columnMasks != null) {
                for (var directive : columnMasks) {
                    if (directive != null && directive.columnRef() != null && !directive.columnRef().isBlank()) {
                        register(directive.columnRef(), new AppliedMask(directive.strategy(),
                                directive.params(), directive.policyId()), true);
                    }
                }
            }
            if (restrictedColumns != null) {
                for (var entry : restrictedColumns) {
                    if (entry != null && !entry.isBlank()) {
                        register(entry, new AppliedMask(MaskingStrategy.FULL, Map.of(), null), false);
                    }
                }
            }
        }

        private void register(String ref, AppliedMask mask, boolean fromDirective) {
            var segments = ref.trim().toLowerCase(Locale.ROOT).split("\\.");
            var prop = segments[segments.length - 1];
            if (segments.length >= 2) {
                var label = segments[segments.length - 2];
                labelMasks.computeIfAbsent(label, k -> new LinkedHashMap<>())
                        .merge(prop, mask, (existing, incoming) -> fromDirective ? incoming : existing);
            } else {
                bareMasks.merge(prop, mask, (existing, incoming) -> fromDirective ? incoming : existing);
            }
        }

        Object maskColumn(String columnName, Object value, MaskSink sink) {
            return switch (value) {
                case null -> null;
                case Map<?, ?> map -> maskMap(map, sink);
                case List<?> list -> maskList(list, sink);
                default -> {
                    var mask = columnLevelMask(columnName.toLowerCase(Locale.ROOT));
                    yield mask == null ? value : applyScalar(value, mask, sink);
                }
            };
        }

        /** A top-level scalar column is masked when any directive/restricted property matches its name. */
        private AppliedMask columnLevelMask(String columnName) {
            for (var byProp : labelMasks.values()) {
                var mask = byProp.get(columnName);
                if (mask != null) {
                    return mask;
                }
            }
            return bareMasks.get(columnName);
        }

        private Object maskMap(Map<?, ?> map, MaskSink sink) {
            var labels = nodeLabels(map);
            var out = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                var mask = propertyMask(labels, key.toLowerCase(Locale.ROOT));
                if (mask != null) {
                    out.put(key, applyValue(entry.getValue(), mask, sink));
                } else {
                    out.put(key, recurse(entry.getValue(), sink));
                }
            }
            return out;
        }

        private Object maskList(List<?> list, MaskSink sink) {
            var out = new ArrayList<>(list.size());
            for (var element : list) {
                out.add(recurse(element, sink));
            }
            return out;
        }

        private Object recurse(Object value, MaskSink sink) {
            return switch (value) {
                case Map<?, ?> map -> maskMap(map, sink);
                case List<?> list -> maskList(list, sink);
                default -> value;
            };
        }

        /** The mask for a property inside a map: a label-qualified one wins over a bare one. */
        private AppliedMask propertyMask(Set<String> labels, String property) {
            for (var label : labels) {
                var byProp = labelMasks.get(label);
                if (byProp != null && byProp.containsKey(property)) {
                    return byProp.get(property);
                }
            }
            return bareMasks.get(property);
        }

        private static Set<String> nodeLabels(Map<?, ?> map) {
            var labels = new LinkedHashSet<String>();
            var rawLabels = map.get(LABELS_KEY);
            if (rawLabels instanceof List<?> list) {
                for (var label : list) {
                    labels.add(String.valueOf(label).toLowerCase(Locale.ROOT));
                }
            }
            var type = map.get(TYPE_KEY);
            if (type != null) {
                labels.add(String.valueOf(type).toLowerCase(Locale.ROOT));
            }
            return labels;
        }

        /** Mask a property value: recurse structures (FULL collapses them) and mask scalar leaves. */
        private Object applyValue(Object value, AppliedMask mask, MaskSink sink) {
            sink.record(mask.policyId());
            if (mask.strategy() == MaskingStrategy.FULL) {
                return ColumnMasker.FULL_MASK;
            }
            if (value instanceof Map<?, ?> map) {
                var out = new LinkedHashMap<String, Object>();
                for (var entry : map.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), applyValue(entry.getValue(), mask, sink));
                }
                return out;
            }
            if (value instanceof List<?> list) {
                var out = new ArrayList<>(list.size());
                for (var element : list) {
                    out.add(applyValue(element, mask, sink));
                }
                return out;
            }
            return value == null ? null : ColumnMasker.apply(mask.strategy(), String.valueOf(value),
                    mask.params());
        }

        private Object applyScalar(Object value, AppliedMask mask, MaskSink sink) {
            sink.record(mask.policyId());
            if (mask.strategy() == MaskingStrategy.FULL) {
                return ColumnMasker.FULL_MASK;
            }
            return ColumnMasker.apply(mask.strategy(), String.valueOf(value), mask.params());
        }
    }
}
