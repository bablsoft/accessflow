import { describe, expect, it } from 'vitest';
import { filterSchema, columnMatches } from '../schemaFilter';
import type { DatasourceSchema } from '@/types/api';

const schema: DatasourceSchema = {
  schemas: [
    {
      name: 'public',
      tables: [
        {
          name: 'users',
          columns: [
            { name: 'id', type: 'uuid', nullable: false, primary_key: true },
            { name: 'email', type: 'varchar', nullable: false, primary_key: false },
          ],
          foreign_keys: [],
        },
        {
          name: 'orders',
          columns: [{ name: 'total', type: 'numeric', nullable: false, primary_key: false }],
          foreign_keys: [],
        },
      ],
    },
    {
      name: 'analytics',
      tables: [
        {
          name: 'events',
          columns: [{ name: 'ts', type: 'timestamptz', nullable: false, primary_key: false }],
          foreign_keys: [],
        },
      ],
    },
  ],
};

describe('filterSchema', () => {
  it('returns the schema unchanged for a blank query', () => {
    expect(filterSchema(schema, '')).toBe(schema);
    expect(filterSchema(schema, '   ')).toBe(schema);
  });

  it('matches by table name and drops non-matching tables and schemas', () => {
    const result = filterSchema(schema, 'orders');
    expect(result.schemas).toHaveLength(1);
    expect(result.schemas[0]!.name).toBe('public');
    expect(result.schemas[0]!.tables.map((t) => t.name)).toEqual(['orders']);
  });

  it('matches by column name and keeps the whole matched table', () => {
    const result = filterSchema(schema, 'email');
    expect(result.schemas).toHaveLength(1);
    expect(result.schemas[0]!.tables.map((t) => t.name)).toEqual(['users']);
    // The full column set is preserved so the user sees the whole table.
    expect(result.schemas[0]!.tables[0]!.columns).toHaveLength(2);
  });

  it('matches by schema name and keeps all of its tables', () => {
    const result = filterSchema(schema, 'analytics');
    expect(result.schemas).toHaveLength(1);
    expect(result.schemas[0]!.name).toBe('analytics');
    expect(result.schemas[0]!.tables.map((t) => t.name)).toEqual(['events']);
  });

  it('is case-insensitive', () => {
    expect(filterSchema(schema, 'USERS').schemas[0]!.tables[0]!.name).toBe('users');
  });

  it('returns no schemas when nothing matches', () => {
    expect(filterSchema(schema, 'zzz').schemas).toHaveLength(0);
  });
});

describe('columnMatches', () => {
  it('is true only on a non-empty matching query', () => {
    expect(columnMatches('email', 'mai')).toBe(true);
    expect(columnMatches('email', 'EMAIL')).toBe(true);
    expect(columnMatches('email', '')).toBe(false);
    expect(columnMatches('email', 'id')).toBe(false);
  });
});
