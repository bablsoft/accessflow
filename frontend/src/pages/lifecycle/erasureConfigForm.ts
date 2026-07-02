import type {
  ErasureCondition,
  ErasureConditionOperator,
  ErasureConditionSet,
  SchemaNamespace,
} from '@/types/api';

/**
 * Form-row shape for the structured condition builder (AF-519). Mirrors {@link ErasureCondition}
 * but tolerates the partially-filled state of an AntD Form.List row.
 */
export interface ErasureConditionRow {
  column?: string;
  operator?: ErasureConditionOperator;
  values?: string[];
  negate?: boolean;
}

/** The shared erasure-config fields as they live on a parent AntD form. */
export interface ErasureConfigFormValues {
  target_table?: string | null;
  target_columns?: string[];
  conditions?: ErasureConditionRow[];
  raw_where?: string | null;
}

/** Normalised erasure-config payload pieces ready to merge into a create/submit request body. */
export interface ErasureConfigPayload {
  target_table: string | null;
  target_columns: string[];
  conditions: ErasureConditionSet | null;
  raw_where: string | null;
}

const blankToNull = (s: string | null | undefined): string | null =>
  s == null || s.trim() === '' ? null : s.trim();

/** Convert Form.List rows into a bound {@link ErasureConditionSet} (or null when none). */
export function rowsToConditionSet(rows: ErasureConditionRow[] | undefined): ErasureConditionSet | null {
  const conditions: ErasureCondition[] = (rows ?? [])
    .filter((r) => r && r.column && r.column.trim() !== '')
    .map((r) => ({
      column: (r.column as string).trim(),
      operator: (r.operator ?? 'EQUALS') as ErasureConditionOperator,
      values: r.operator === 'IS_NULL' ? [] : (r.values ?? []),
      negate: r.negate ?? false,
    }));
  return conditions.length > 0 ? { conditions } : null;
}

/** Build the normalised config payload from a parent form's values. */
export function configToPayload(values: ErasureConfigFormValues): ErasureConfigPayload {
  return {
    target_table: blankToNull(values.target_table),
    target_columns: values.target_columns ?? [],
    conditions: rowsToConditionSet(values.conditions),
    raw_where: blankToNull(values.raw_where),
  };
}

/**
 * Mirrors the backend EMPTY_REQUEST shape rule: an erasure request needs a subject identifier,
 * at least one bound structured condition, or a raw WHERE clause.
 */
export function hasErasureScope(values: {
  subject_identifier?: string | null;
  conditions?: ErasureConditionRow[];
  raw_where?: string | null;
}): boolean {
  return (
    blankToNull(values.subject_identifier) !== null ||
    rowsToConditionSet(values.conditions) !== null ||
    blankToNull(values.raw_where) !== null
  );
}

/**
 * Mirrors the backend TARGET_TABLE_REQUIRED shape rule: a target table is required whenever
 * structured conditions or a raw WHERE clause are present.
 */
export function requiresTargetTable(values: {
  conditions?: ErasureConditionRow[];
  raw_where?: string | null;
}): boolean {
  return rowsToConditionSet(values.conditions) !== null || blankToNull(values.raw_where) !== null;
}

/** A `schema.table` pick-list entry with its column names, for the cascading target pickers (#548). */
export interface ErasureTableOption {
  value: string;
  columns: string[];
}

/** Flatten an introspected schema into sorted `schema.table` options with embedded column lists. */
export function schemaTableOptions(
  schemas: ReadonlyArray<SchemaNamespace>,
): ErasureTableOption[] {
  const out: ErasureTableOption[] = [];
  for (const s of schemas) {
    for (const tbl of s.tables) {
      out.push({ value: `${s.name}.${tbl.name}`, columns: tbl.columns.map((c) => c.name) });
    }
  }
  out.sort((a, b) => a.value.localeCompare(b.value));
  return out;
}

/** The selected table's columns, or null when the selection doesn't match a schema table. */
export function columnsForTable(
  tables: ReadonlyArray<ErasureTableOption>,
  selected: string | null | undefined,
): string[] | null {
  if (!selected) return null;
  return tables.find((tbl) => tbl.value === selected)?.columns ?? null;
}

/** Drop column selections that don't exist on the newly selected table (null allowed ⇒ keep all). */
export function pruneColumnsToTable(
  selected: string[] | undefined,
  allowed: string[] | null,
): string[] {
  if (allowed === null) return selected ?? [];
  const allowedSet = new Set(allowed);
  return (selected ?? []).filter((c) => allowedSet.has(c));
}

/** Convert a persisted {@link ErasureConditionSet} back into editable Form.List rows. */
export function conditionSetToRows(set: ErasureConditionSet | null | undefined): ErasureConditionRow[] {
  return (set?.conditions ?? []).map((c) => ({
    column: c.column,
    operator: c.operator,
    values: c.values,
    negate: c.negate,
  }));
}
