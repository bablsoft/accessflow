import { describe, expect, it, vi } from 'vitest';
import type { SchemaNamespace } from '@/types/api';
import { buildLayout } from './erDiagramLayout';

const fkLabel = (from: string, to: string) => `${from}->${to}`;

function fixture(): SchemaNamespace[] {
  return [
    {
      name: 'public',
      tables: [
        {
          name: 'users',
          columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
          foreign_keys: [],
        },
        {
          name: 'orders',
          columns: [
            { name: 'id', type: 'uuid', nullable: false, primary_key: true },
            { name: 'user_id', type: 'uuid', nullable: false, primary_key: false },
          ],
          foreign_keys: [{ from_column: 'user_id', to_table: 'users', to_column: 'id' }],
        },
      ],
    },
  ];
}

describe('buildLayout', () => {
  it('produces one node per table and one edge per foreign key', () => {
    const onSelect = vi.fn();
    const { nodes, edges } = buildLayout(fixture(), null, onSelect, 'PK', fkLabel);

    expect(nodes).toHaveLength(2);
    expect(nodes.map((n) => n.id).sort()).toEqual(['public.orders', 'public.users']);
    expect(edges).toHaveLength(1);
    expect(edges[0]!.source).toBe('public.orders');
    expect(edges[0]!.target).toBe('public.users');
    expect(edges[0]!.label).toBe('user_id->id');
  });

  it('positions every node with non-zero coordinates from dagre', () => {
    const { nodes } = buildLayout(fixture(), null, vi.fn(), 'PK', fkLabel);
    for (const n of nodes) {
      expect(typeof n.position.x).toBe('number');
      expect(typeof n.position.y).toBe('number');
    }
    const xs = nodes.map((n) => n.position.x);
    expect(new Set(xs).size).toBeGreaterThan(1);
  });

  it('dims edges that do not touch the selected node', () => {
    const onSelect = vi.fn();
    const { edges } = buildLayout(fixture(), 'public.users', onSelect, 'PK', fkLabel);
    const edge = edges[0]!;
    expect(edge.style?.opacity).toBe(1);

    const otherSchemas: SchemaNamespace[] = [
      {
        name: 'public',
        tables: [
          {
            name: 'parent',
            columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
            foreign_keys: [],
          },
          {
            name: 'child',
            columns: [
              { name: 'id', type: 'uuid', nullable: false, primary_key: true },
              { name: 'parent_id', type: 'uuid', nullable: false, primary_key: false },
            ],
            foreign_keys: [{ from_column: 'parent_id', to_table: 'parent', to_column: 'id' }],
          },
          {
            name: 'orphan',
            columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
            foreign_keys: [],
          },
        ],
      },
    ];
    const dimmed = buildLayout(otherSchemas, 'public.orphan', vi.fn(), 'PK', fkLabel);
    expect(dimmed.edges[0]!.style?.opacity).toBeLessThan(1);
  });

  it('skips foreign keys that point at unknown tables', () => {
    const schemas: SchemaNamespace[] = [
      {
        name: 'public',
        tables: [
          {
            name: 'orders',
            columns: [
              { name: 'id', type: 'uuid', nullable: false, primary_key: true },
              { name: 'user_id', type: 'uuid', nullable: false, primary_key: false },
            ],
            foreign_keys: [{ from_column: 'user_id', to_table: 'ghost', to_column: 'id' }],
          },
        ],
      },
    ];
    const { edges } = buildLayout(schemas, null, vi.fn(), 'PK', fkLabel);
    expect(edges).toHaveLength(0);
  });

  it('resolves a foreign key target in a different schema when missing from the source schema', () => {
    const schemas: SchemaNamespace[] = [
      {
        name: 'app',
        tables: [
          {
            name: 'orders',
            columns: [
              { name: 'id', type: 'uuid', nullable: false, primary_key: true },
              { name: 'user_id', type: 'uuid', nullable: false, primary_key: false },
            ],
            foreign_keys: [{ from_column: 'user_id', to_table: 'users', to_column: 'id' }],
          },
        ],
      },
      {
        name: 'auth',
        tables: [
          {
            name: 'users',
            columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
            foreign_keys: [],
          },
        ],
      },
    ];
    const { edges } = buildLayout(schemas, null, vi.fn(), 'PK', fkLabel);
    expect(edges).toHaveLength(1);
    expect(edges[0]!.source).toBe('app.orders');
    expect(edges[0]!.target).toBe('auth.users');
  });
});
