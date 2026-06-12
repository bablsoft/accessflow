package com.bablsoft.accessflow.engine.cassandra;

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
 * Materializes a page of CQL result rows into the engine-neutral {@link SelectExecutionResult}.
 * Unlike the document engines, CQL rows are already flat — the column set comes verbatim from the
 * driver's {@code ColumnDefinitions}, so no document-unwrap is needed. Restricted columns and
 * masking policies are applied per value via the shared {@link ColumnMasker} with the same
 * {@code table.column} → bare {@code column} precedence as the SQL and Couchbase engines.
 */
class CassandraResultMapper {

    /** A result column: its name and the CQL type name (e.g. {@code text}, {@code int}, {@code uuid}). */
    record CqlColumn(String name, String typeName) {
    }

    SelectExecutionResult materialize(List<CqlColumn> columns, List<List<Object>> fetched, int maxRows,
                                      Duration duration, List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) {
        boolean truncated = fetched.size() > maxRows;
        var page = truncated ? fetched.subList(0, maxRows) : fetched;
        var matcher = new MaskMatcher(restrictedColumns, columnMasks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();

        var resultColumns = new ArrayList<ResultColumn>(columns.size());
        var maskByIndex = new ArrayList<MaskMatcher.AppliedMask>(columns.size());
        for (var column : columns) {
            var mask = matcher.maskFor(column.name());
            maskByIndex.add(mask);
            resultColumns.add(new ResultColumn(column.name(), Types.OTHER, column.typeName(),
                    mask != null));
            if (mask != null && mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
        }

        var rows = new ArrayList<List<Object>>(page.size());
        for (var fetchedRow : page) {
            var row = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                var raw = i < fetchedRow.size() ? fetchedRow.get(i) : null;
                var mask = maskByIndex.get(i);
                row.add(mask == null ? raw : maskValue(raw, mask));
            }
            rows.add(row);
        }
        return new SelectExecutionResult(resultColumns, rows, rows.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
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

    /**
     * Resolves the masking that applies to a column, mirroring the SQL {@code ColumnMaskResolver}
     * precedence ({@code table.column} → bare {@code column}). Explicit mask directives win over a
     * bare restricted-columns entry, which defaults to {@link MaskingStrategy#FULL}.
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

            /** 2 = table.column, 1 = bare column, 0 = no match. */
            int matchLevel(String column) {
                if (table != null && table.endsWith("." + column)) {
                    return 2;
                }
                return bare.equals(column) ? 1 : 0;
            }
        }
    }
}
