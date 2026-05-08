import { describe, expect, it } from 'vitest';
import { flattenSchemaToColumns } from './schemaColumns';
import type { SchemaNamespace } from '@/types/api';

describe('flattenSchemaToColumns', () => {
  it('returns empty list for empty input', () => {
    expect(flattenSchemaToColumns([])).toEqual([]);
  });

  it('flattens schema → table → columns into schema.table.column triplets', () => {
    const schemas: SchemaNamespace[] = [
      {
        name: 'public',
        tables: [
          {
            name: 'users',
            columns: [
              { name: 'id', type: 'uuid', nullable: false, primary_key: true },
              { name: 'email', type: 'text', nullable: true, primary_key: false },
            ],
          },
        ],
      },
    ];

    const result = flattenSchemaToColumns(schemas);

    expect(result).toEqual([
      { value: 'public.users.email', label: 'public.users.email' },
      { value: 'public.users.id', label: 'public.users.id' },
    ]);
  });

  it('sorts options alphabetically across schemas and tables', () => {
    const schemas: SchemaNamespace[] = [
      {
        name: 'public',
        tables: [
          {
            name: 'orders',
            columns: [
              { name: 'amount', type: 'numeric', nullable: false, primary_key: false },
            ],
          },
        ],
      },
      {
        name: 'audit',
        tables: [
          {
            name: 'events',
            columns: [
              { name: 'ts', type: 'timestamptz', nullable: false, primary_key: false },
            ],
          },
        ],
      },
    ];

    const result = flattenSchemaToColumns(schemas);

    expect(result.map((o) => o.value)).toEqual([
      'audit.events.ts',
      'public.orders.amount',
    ]);
  });

  it('handles tables with no columns', () => {
    const schemas: SchemaNamespace[] = [
      { name: 'public', tables: [{ name: 'empty', columns: [] }] },
    ];

    expect(flattenSchemaToColumns(schemas)).toEqual([]);
  });
});
