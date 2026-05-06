import { describe, expect, it } from 'vitest';
import { buildMockSchema } from '../schema';
import { DATASOURCES } from '../data';

describe('buildMockSchema', () => {
  it('returns a single public schema with the seed tables', () => {
    const ds = DATASOURCES.find((d) => d.db_type === 'POSTGRESQL')!;
    const schema = buildMockSchema(ds);
    expect(schema.schemas).toHaveLength(1);
    expect(schema.schemas[0]!.name).toBe('public');
    const tableNames = schema.schemas[0]!.tables.map((t) => t.name);
    expect(tableNames).toContain('users');
    expect(tableNames).toContain('orders');
    expect(tableNames).toContain('audit_events');
  });

  it('uses uuid type for users.id on PostgreSQL', () => {
    const ds = DATASOURCES.find((d) => d.db_type === 'POSTGRESQL')!;
    const users = buildMockSchema(ds).schemas[0]!.tables.find((t) => t.name === 'users')!;
    expect(users.columns.find((c) => c.name === 'id')?.type).toBe('uuid');
  });

  it('uses binary(16) for users.id on MySQL', () => {
    const ds = DATASOURCES.find((d) => d.db_type === 'MYSQL')!;
    const users = buildMockSchema(ds).schemas[0]!.tables.find((t) => t.name === 'users')!;
    expect(users.columns.find((c) => c.name === 'id')?.type).toBe('binary(16)');
  });

  it('marks primary keys correctly', () => {
    const ds = DATASOURCES[0]!;
    const orders = buildMockSchema(ds).schemas[0]!.tables.find((t) => t.name === 'orders')!;
    expect(orders.columns.find((c) => c.primary_key)?.name).toBe('id');
  });
});
