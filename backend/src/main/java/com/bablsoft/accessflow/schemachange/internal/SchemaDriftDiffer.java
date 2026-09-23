package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * Compares a baseline {@link DatabaseSchemaView} against a scanned one and emits ordered drift
 * findings (#881). Pure and static: no Spring, no clock, no persistence — the time budget arrives as
 * a {@link BooleanSupplier} so the tests can drive it without sleeping.
 *
 * <p>Three rules carry the design:
 *
 * <ul>
 *   <li><b>Never descend past an absence.</b> A schema missing on one side is exactly one finding;
 *       its tables and columns are not emitted and do not count against the table cap. Same for a
 *       table. A dropped 400-column table is one row, not 401.</li>
 *   <li><b>One finding per (path, kind).</b> A column both retyped and made nullable yields two
 *       findings on the same path: each has its own remediation and its own resolve lifecycle, and
 *       fixing one must not silently close the other.</li>
 *   <li><b>The table cap applies to the union</b> of both sides. Capping the scanned side alone
 *       would report every baseline table outside the window as missing; capping the baseline side
 *       alone would hide real additions.</li>
 * </ul>
 */
final class SchemaDriftDiffer {

    private SchemaDriftDiffer() {
    }

    /** Limits for one diff. Both are hard caps that flag the scan partial rather than failing it. */
    record DiffLimits(int maxTables, int maxFindings) {
    }

    /** One drifted object path. {@code expectedValue} is the baseline's side, {@code actualValue} the target's. */
    record DriftFinding(String objectPath, SchemaDriftFindingKind kind, String expectedValue, String actualValue) {
    }

    /**
     * @param findings          emitted in a deterministic order, so the cap's truncation prefix and
     *                          therefore {@code reachedTableKeys} are reproducible
     * @param reachedTableKeys  lowercased {@code schema.table} keys this diff actually compared (or
     *                          emitted as a whole-table absence). Reconciliation may only resolve
     *                          findings under these — a table the cap or the budget cut off keeps
     *                          its findings untouched
     * @param knownTableKeys    every lowercased {@code schema.table} key of a schema both sides have,
     *                          before any cap — reconciliation maps a stored finding to its table by
     *                          the longest of these that prefixes its path, since object names may
     *                          themselves contain dots (nested Elasticsearch fields, BigQuery RECORD
     *                          fields, dotted index names) and a path cannot be split back apart
     * @param schemaLevelComplete whether every schema-level finding was recorded — false when the
     *                          findings cap cut the schema comparison itself short
     * @param foreignKeysSuppressed whether foreign-key comparison was skipped scan-wide
     * @param partial           the table cap, the findings cap or the time budget cut the diff short
     */
    record DiffResult(List<DriftFinding> findings, Set<String> reachedTableKeys, Set<String> knownTableKeys,
                      boolean schemaLevelComplete, boolean foreignKeysSuppressed, boolean partial) {
    }

    private record TableRef(String schemaName, String tableName, DatabaseSchemaView.Table table) {
    }

    static DiffResult diff(DatabaseSchemaView baseline, DatabaseSchemaView target, DiffLimits limits,
                           BooleanSupplier outOfTime) {
        var state = new State(limits, outOfTime, foreignKeysSuppressed(baseline, target));

        var baselineSchemas = indexSchemas(baseline);
        var targetSchemas = indexSchemas(target);

        var comparable = new ArrayList<String>();
        for (var schemaKey : union(baselineSchemas.keySet(), targetSchemas.keySet())) {
            var inBaseline = baselineSchemas.get(schemaKey);
            var inTarget = targetSchemas.get(schemaKey);
            if (inBaseline == null) {
                state.emit(displayName(inTarget), SchemaDriftFindingKind.UNEXPECTED_IN_TARGET,
                        null, schemaSummary(inTarget));
            } else if (inTarget == null) {
                state.emit(displayName(inBaseline), SchemaDriftFindingKind.MISSING_IN_TARGET,
                        schemaSummary(inBaseline), null);
            } else {
                comparable.add(schemaKey);
            }
        }

        var schemaLevelComplete = !state.dropped;

        var baselineTables = indexTables(baselineSchemas, comparable);
        var targetTables = indexTables(targetSchemas, comparable);
        var knownTableKeys = Set.copyOf(union(baselineTables.keySet(), targetTables.keySet()));
        var tableKeys = new ArrayList<>(union(baselineTables.keySet(), targetTables.keySet()));
        if (tableKeys.size() > limits.maxTables()) {
            tableKeys = new ArrayList<>(tableKeys.subList(0, limits.maxTables()));
            state.partial = true;
        }

        for (var tableKey : tableKeys) {
            if (state.stopped() || outOfTime.getAsBoolean()) {
                state.partial = true;
                break;
            }
            var inBaseline = baselineTables.get(tableKey);
            var inTarget = targetTables.get(tableKey);
            if (inBaseline == null) {
                state.emit(path(inTarget), SchemaDriftFindingKind.UNEXPECTED_IN_TARGET,
                        null, tableSummary(inTarget));
            } else if (inTarget == null) {
                state.emit(path(inBaseline), SchemaDriftFindingKind.MISSING_IN_TARGET,
                        tableSummary(inBaseline), null);
            } else {
                diffTable(state, inBaseline, inTarget);
            }
            if (!state.abandonedTable) {
                state.reached.add(tableKey);
            }
            state.abandonedTable = false;
        }

        return new DiffResult(List.copyOf(state.findings.values()), Set.copyOf(state.reached), knownTableKeys,
                schemaLevelComplete, state.foreignKeysSuppressed, state.partial);
    }

    private static void diffTable(State state, TableRef baseline, TableRef target) {
        var tablePath = path(baseline);
        var baselineColumns = indexColumns(baseline.table());
        var targetColumns = indexColumns(target.table());

        for (var columnKey : union(baselineColumns.keySet(), targetColumns.keySet())) {
            if (state.stopped()) {
                state.abandonedTable = true;
                return;
            }
            var inBaseline = baselineColumns.get(columnKey);
            var inTarget = targetColumns.get(columnKey);
            if (inBaseline == null) {
                state.emit(tablePath + '.' + inTarget.name(), SchemaDriftFindingKind.UNEXPECTED_IN_TARGET,
                        null, columnSummary(inTarget));
                continue;
            }
            if (inTarget == null) {
                state.emit(tablePath + '.' + inBaseline.name(), SchemaDriftFindingKind.MISSING_IN_TARGET,
                        columnSummary(inBaseline), null);
                continue;
            }
            var columnPath = tablePath + '.' + inBaseline.name();
            if (!equalsIgnoringCase(inBaseline.type(), inTarget.type())) {
                state.emit(columnPath, SchemaDriftFindingKind.TYPE_MISMATCH,
                        inBaseline.type(), inTarget.type());
            }
            if (inBaseline.nullable() != inTarget.nullable()) {
                state.emit(columnPath, SchemaDriftFindingKind.NULLABILITY_MISMATCH,
                        nullability(inBaseline.nullable()), nullability(inTarget.nullable()));
            }
            if (inBaseline.primaryKey() != inTarget.primaryKey()) {
                state.emit(columnPath, SchemaDriftFindingKind.PRIMARY_KEY_MISMATCH,
                        primaryKey(inBaseline.primaryKey()), primaryKey(inTarget.primaryKey()));
            }
        }

        if (!state.foreignKeysSuppressed) {
            diffForeignKeys(state, tablePath, baseline.table(), target.table());
        }
    }

    /**
     * A {@link DatabaseSchemaView.ForeignKey} has no owning table and no target schema, so the stable
     * unit is one finding per owning column carrying that column's whole reference set on each side.
     * That makes an added or retargeted key one finding rather than a missing/unexpected pair, and a
     * composite key — already stored as independent per-column records — reads as its columns.
     */
    private static void diffForeignKeys(State state, String tablePath, DatabaseSchemaView.Table baseline,
                                        DatabaseSchemaView.Table target) {
        var baselineRefs = indexForeignKeys(baseline);
        var targetRefs = indexForeignKeys(target);
        for (var columnKey : union(baselineRefs.keySet(), targetRefs.keySet())) {
            if (state.stopped()) {
                state.abandonedTable = true;
                return;
            }
            var expected = baselineRefs.get(columnKey);
            var actual = targetRefs.get(columnKey);
            if (expected != null && expected.equals(actual)) {
                continue;
            }
            state.emit(tablePath + '.' + columnKey, SchemaDriftFindingKind.FOREIGN_KEY_MISMATCH,
                    references(expected), references(actual));
        }
    }

    /**
     * The JDBC introspector swallows the per-table {@code SQLException} from {@code getImportedKeys}
     * and returns an empty list, and several catalog engines never report foreign keys at all — so
     * "no keys" and "the read failed" are indistinguishable. One side reporting none while the other
     * reports some is therefore almost never a real schema, and comparing them would report every
     * foreign key in the estate as drift on every scan. Per-table differences stay compared: one
     * table losing its keys is real drift, and a one-table metadata failure self-resolves next scan.
     */
    private static boolean foreignKeysSuppressed(DatabaseSchemaView baseline, DatabaseSchemaView target) {
        var inBaseline = countForeignKeys(baseline);
        var inTarget = countForeignKeys(target);
        return (inBaseline == 0) != (inTarget == 0);
    }

    private static long countForeignKeys(DatabaseSchemaView view) {
        if (view == null || view.schemas() == null) {
            return 0;
        }
        return view.schemas().stream()
                .filter(s -> s != null && s.tables() != null)
                .flatMap(s -> s.tables().stream())
                .filter(t -> t != null && t.foreignKeys() != null)
                .mapToLong(t -> t.foreignKeys().size())
                .sum();
    }

    // --- indexing -----------------------------------------------------------------------------

    private static Map<String, DatabaseSchemaView.Schema> indexSchemas(DatabaseSchemaView view) {
        Map<String, DatabaseSchemaView.Schema> indexed = new TreeMap<>();
        if (view == null || view.schemas() == null) {
            return indexed;
        }
        for (var schema : view.schemas()) {
            if (schema != null) {
                indexed.putIfAbsent(key(schema.name()), schema);
            }
        }
        return indexed;
    }

    private static Map<String, TableRef> indexTables(Map<String, DatabaseSchemaView.Schema> schemas,
                                                     List<String> schemaKeys) {
        Map<String, TableRef> indexed = new TreeMap<>();
        for (var schemaKey : schemaKeys) {
            var schema = schemas.get(schemaKey);
            if (schema == null || schema.tables() == null) {
                continue;
            }
            for (var table : schema.tables()) {
                if (table != null) {
                    indexed.putIfAbsent(schemaKey + '.' + key(table.name()),
                            new TableRef(displayName(schema), table.name(), table));
                }
            }
        }
        return indexed;
    }

    private static Map<String, DatabaseSchemaView.Column> indexColumns(DatabaseSchemaView.Table table) {
        Map<String, DatabaseSchemaView.Column> indexed = new TreeMap<>();
        if (table.columns() == null) {
            return indexed;
        }
        for (var column : table.columns()) {
            if (column != null) {
                indexed.putIfAbsent(key(column.name()), column);
            }
        }
        return indexed;
    }

    /** Owning column key to its sorted set of {@code toTable.toColumn} references. */
    private static Map<String, Set<String>> indexForeignKeys(DatabaseSchemaView.Table table) {
        Map<String, Set<String>> indexed = new TreeMap<>();
        if (table.foreignKeys() == null) {
            return indexed;
        }
        for (var foreignKey : table.foreignKeys()) {
            if (foreignKey == null) {
                continue;
            }
            indexed.computeIfAbsent(key(foreignKey.fromColumn()), k -> new TreeSet<>())
                    .add(key(foreignKey.toTable()) + '.' + key(foreignKey.toColumn()));
        }
        return indexed;
    }

    // --- rendering ----------------------------------------------------------------------------

    private static String references(Set<String> refs) {
        return refs == null || refs.isEmpty() ? "(none)" : String.join(", ", refs);
    }

    private static String schemaSummary(DatabaseSchemaView.Schema schema) {
        var tables = schema.tables() == null ? 0 : schema.tables().size();
        return displayName(schema) + " (" + tables + " tables)";
    }

    private static String tableSummary(TableRef ref) {
        var columns = ref.table().columns() == null ? 0 : ref.table().columns().size();
        return nullSafe(ref.tableName()) + " (" + columns + " columns)";
    }

    private static String columnSummary(DatabaseSchemaView.Column column) {
        return nullSafe(column.type()) + ' ' + nullability(column.nullable())
                + (column.primaryKey() ? " PRIMARY KEY" : "");
    }

    private static String nullability(boolean nullable) {
        return nullable ? "NULL" : "NOT NULL";
    }

    private static String primaryKey(boolean primaryKey) {
        return primaryKey ? "PRIMARY KEY" : "NOT PRIMARY KEY";
    }

    private static String path(TableRef ref) {
        return ref.schemaName() + '.' + nullSafe(ref.tableName());
    }

    private static String displayName(DatabaseSchemaView.Schema schema) {
        return nullSafe(schema.name());
    }

    private static boolean equalsIgnoringCase(String left, String right) {
        return key(left).equals(key(right));
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static Collection<String> union(Set<String> left, Set<String> right) {
        var union = new TreeSet<>(left);
        union.addAll(right);
        return union;
    }

    /** Mutable emission state: the cap, the dedupe map and the reached set. */
    private static final class State {

        private final DiffLimits limits;
        private final BooleanSupplier outOfTime;
        private final boolean foreignKeysSuppressed;
        private final Map<String, DriftFinding> findings = new LinkedHashMap<>();
        private final Set<String> reached = new LinkedHashSet<>();
        private boolean partial;
        private boolean abandonedTable;
        private boolean dropped;

        private State(DiffLimits limits, BooleanSupplier outOfTime, boolean foreignKeysSuppressed) {
            this.limits = limits;
            this.outOfTime = outOfTime;
            this.foreignKeysSuppressed = foreignKeysSuppressed;
        }

        private boolean stopped() {
            if (findings.size() >= limits.maxFindings()) {
                partial = true;
                return true;
            }
            return false;
        }

        private void emit(String objectPath, SchemaDriftFindingKind kind, String expected, String actual) {
            if (stopped()) {
                abandonedTable = true;
                dropped = true;
                return;
            }
            // One finding per (path, kind). A duplicate would be a bug in the walk above, never input.
            findings.putIfAbsent(key(objectPath) + '|' + kind.name(),
                    new DriftFinding(objectPath, kind, expected, actual));
        }
    }
}
