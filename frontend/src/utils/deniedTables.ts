/** Backend `@Size(max = 50)` on `denied_schemas` (#939). */
export const DENIED_SCHEMAS_MAX = 50;

/** Backend `@Size(max = 200)` on `denied_tables` (#939). */
export const DENIED_TABLES_MAX = 200;

/** Mirrors backend `DeniedTables.SCHEMA_ENTRY_PATTERN`: one name, no dots or wildcards. */
const SCHEMA_ENTRY = /^[^.*?]*[^.*?\s][^.*?]*$/;

/** Mirrors backend `DeniedTables.TABLE_ENTRY_PATTERN`: `table`, `schema.table` or `schema.*`. */
const TABLE_ENTRY = /^[^.*?]*[^.*?\s][^.*?]*(\.[^.*?]*[^.*?\s][^.*?]*)*(\.\*)?$/;

export const isValidDeniedSchema = (entry: string): boolean => SCHEMA_ENTRY.test(entry);

export const isValidDeniedTable = (entry: string): boolean => TABLE_ENTRY.test(entry);

/** Mirrors the backend `@NotBlank` on every deny-list item. */
export const hasBlankEntry = (entries: string[]): boolean =>
  entries.some((entry) => entry.trim() === '');

/**
 * One display list for a grant's table deny-lists: a denied schema reads as `schema.*`, a denied
 * table as written.
 */
export const deniedTableEntries = (
  schemas: string[] | null | undefined,
  tables: string[] | null | undefined,
): string[] => [...(schemas ?? []).map((schema) => `${schema}.*`), ...(tables ?? [])];
