import type { DatasourceSchema, SchemaNamespace, SchemaTable } from '@/types/api';

/**
 * Cross-hierarchy schema filter for the searchable object tree (AF-443). A query matches across
 * schema names, table names, AND column names. A table is kept when its own name matches, its
 * schema name matches, or any of its columns match — and a matched table keeps all its columns so
 * the user sees the full table. A schema is kept when its name matches or it has any kept table.
 * An empty/blank query returns the schema unchanged.
 */
export function filterSchema(schema: DatasourceSchema, query: string): DatasourceSchema {
  const q = query.trim().toLowerCase();
  if (!q) {
    return schema;
  }
  const schemas = schema.schemas
    .map((ns) => filterNamespace(ns, q))
    .filter((ns): ns is SchemaNamespace => ns !== null);
  return { schemas };
}

function filterNamespace(ns: SchemaNamespace, q: string): SchemaNamespace | null {
  const schemaMatches = ns.name.toLowerCase().includes(q);
  const tables = schemaMatches
    ? ns.tables
    : ns.tables
        .map((table) => filterTable(table, q))
        .filter((table): table is SchemaTable => table !== null);
  if (!schemaMatches && tables.length === 0) {
    return null;
  }
  return { name: ns.name, tables };
}

function filterTable(table: SchemaTable, q: string): SchemaTable | null {
  if (table.name.toLowerCase().includes(q)) {
    return table;
  }
  if (table.columns.some((c) => c.name.toLowerCase().includes(q))) {
    return table;
  }
  return null;
}

/** True when a column name itself matches the query — used to highlight matching columns. */
export function columnMatches(columnName: string, query: string): boolean {
  const q = query.trim().toLowerCase();
  return q.length > 0 && columnName.toLowerCase().includes(q);
}
