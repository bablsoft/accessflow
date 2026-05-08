import type { SchemaNamespace } from '@/types/api';

export interface SchemaColumnOption {
  value: string;
  label: string;
}

export function flattenSchemaToColumns(
  schemas: ReadonlyArray<SchemaNamespace>,
): SchemaColumnOption[] {
  const opts: SchemaColumnOption[] = [];
  for (const s of schemas) {
    for (const t of s.tables) {
      for (const c of t.columns) {
        const value = `${s.name}.${t.name}.${c.name}`;
        opts.push({ value, label: value });
      }
    }
  }
  opts.sort((a, b) => a.value.localeCompare(b.value));
  return opts;
}
