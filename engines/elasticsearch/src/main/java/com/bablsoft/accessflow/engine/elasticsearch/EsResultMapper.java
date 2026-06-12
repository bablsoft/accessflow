package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import tools.jackson.databind.JsonNode;

import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Materializes a page of Elasticsearch / OpenSearch search hits into the engine-neutral
 * {@link SelectExecutionResult}. Columns lead with the meta fields ({@code _id}, {@code _index}, and
 * {@code _score} only when no explicit sort is set, since a sorted search returns a null score),
 * then the ordered union of top-level {@code _source} field names across the page (so heterogeneous
 * documents still render as a table). Nested objects / arrays are preserved as {@link Map} /
 * {@link List} for the document view. Restricted columns and masking policies are applied per value
 * via the shared {@link ColumnMasker} — recursively, by dot-path, so a mask on {@code user.email}
 * redacts the nested leaf while the rest of {@code user} stays visible, and the top-level column is
 * flagged restricted when it or any descendant path has a rule.
 */
class EsResultMapper {

    SelectExecutionResult materializeSearch(JsonNode hits, boolean sortPresent, int maxRows,
                                            Duration duration, List<String> restrictedColumns,
                                            List<ColumnMaskDirective> columnMasks) {
        var all = new ArrayList<JsonNode>();
        if (hits != null && hits.isArray()) {
            hits.forEach(all::add);
        }
        boolean truncated = all.size() > maxRows;
        var page = truncated ? all.subList(0, maxRows) : all;

        var columnNames = new LinkedHashSet<String>();
        columnNames.add("_id");
        columnNames.add("_index");
        if (!sortPresent) {
            columnNames.add("_score");
        }
        for (var hit : page) {
            var source = hit.get("_source");
            if (source != null && source.isObject()) {
                for (var entry : source.properties()) {
                    columnNames.add(entry.getKey());
                }
            }
        }

        var matcher = new MaskMatcher(restrictedColumns, columnMasks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();

        var columns = new ArrayList<ResultColumn>(columnNames.size());
        for (var name : columnNames) {
            boolean restricted = matcher.maskForPath(name) != null || matcher.hasRuleAtOrUnder(name);
            columns.add(new ResultColumn(name, Types.OTHER, columnType(name, page), restricted));
        }

        var rows = new ArrayList<List<Object>>(page.size());
        for (var hit : page) {
            var source = hit.get("_source");
            var row = new ArrayList<Object>(columnNames.size());
            for (var name : columnNames) {
                row.add(maskValue(valueOf(hit, source, name), name, matcher, appliedPolicyIds));
            }
            rows.add(row);
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated, duration,
                appliedPolicyIds);
    }

    SelectExecutionResult materializeCount(long count, Duration duration) {
        var columns = List.of(new ResultColumn("count", Types.BIGINT, "long", false));
        var row = new ArrayList<Object>(1);
        row.add(count);
        return new SelectExecutionResult(columns, List.of(row), 1, false, duration);
    }

    private static JsonNode valueOf(JsonNode hit, JsonNode source, String name) {
        if (name.equals("_id") || name.equals("_index") || name.equals("_score")) {
            return hit.get(name);
        }
        return source != null && source.isObject() ? source.get(name) : null;
    }

    private static String columnType(String name, List<JsonNode> page) {
        if (name.equals("_id") || name.equals("_index")) {
            return "keyword";
        }
        if (name.equals("_score")) {
            return "double";
        }
        for (var hit : page) {
            var source = hit.get("_source");
            if (source != null && source.isObject()) {
                var value = source.get(name);
                if (value != null && !value.isNull()) {
                    return EsJson.esTypeName(value);
                }
            }
        }
        return "text";
    }

    private static Object maskValue(JsonNode value, String path, MaskMatcher matcher,
                                    LinkedHashSet<UUID> appliedPolicyIds) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        var mask = matcher.maskForPath(path);
        if (mask != null) {
            if (mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
            if (mask.strategy() == MaskingStrategy.FULL) {
                return ColumnMasker.FULL_MASK;
            }
            return ColumnMasker.apply(mask.strategy(), EsJson.scalarString(value), mask.params());
        }
        if (value.isObject()) {
            var out = new LinkedHashMap<String, Object>();
            for (var entry : value.properties()) {
                out.put(entry.getKey(), maskValue(entry.getValue(), path + "." + entry.getKey(),
                        matcher, appliedPolicyIds));
            }
            return out;
        }
        if (value.isArray()) {
            var out = new ArrayList<>(value.size());
            for (var element : value) {
                out.add(maskValue(element, path, matcher, appliedPolicyIds));
            }
            return out;
        }
        return EsJson.toJava(value);
    }

    /**
     * Resolves the masking that applies to a (possibly nested) field path, extending the SQL
     * {@code ColumnMaskResolver} precedence to dot-paths: an explicit mask directive whose
     * {@code columnRef} is the exact path wins over an index-qualified form, which wins over a bare
     * last-segment match; an unmatched bare restricted-columns entry defaults to
     * {@link MaskingStrategy#FULL}. {@link #hasRuleAtOrUnder(String)} lights up a top-level column's
     * restricted flag when only a nested field under it is masked.
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

        /** 3 = exact path, 2 = index-qualified (…​.path), 1 = bare last-segment, 0 = no match. */
        private static int matchLevel(String ref, String path) {
            if (ref.equals(path)) {
                return 3;
            }
            if (ref.endsWith("." + path)) {
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
