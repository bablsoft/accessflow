import type { Datasource, DatasourceSchema, SchemaColumn } from '@/types/api';

interface ColumnSeed {
  name: string;
  type: string;
  primary_key?: boolean;
  nullable?: boolean;
}

const col = (seed: ColumnSeed): SchemaColumn => ({
  name: seed.name,
  type: seed.type,
  nullable: seed.nullable ?? !seed.primary_key,
  primary_key: seed.primary_key ?? false,
});

export function buildMockSchema(ds: Datasource): DatasourceSchema {
  const isPg = ds.db_type === 'POSTGRESQL';
  return {
    schemas: [
      {
        name: 'public',
        tables: [
          { name: 'users', columns: [
            col({ name: 'id', type: isPg ? 'uuid' : 'binary(16)', primary_key: true }),
            col({ name: 'email', type: 'varchar(255)' }),
            col({ name: 'display_name', type: 'varchar(255)' }),
            col({ name: 'password_hash', type: 'varchar(255)' }),
            col({ name: 'role', type: 'varchar(32)' }),
            col({ name: 'is_active', type: 'boolean' }),
            col({ name: 'last_login_at', type: 'timestamptz' }),
            col({ name: 'created_at', type: 'timestamptz' }),
          ]},
          { name: 'orders', columns: [
            col({ name: 'id', type: 'bigint', primary_key: true }),
            col({ name: 'customer_id', type: 'uuid' }),
            col({ name: 'status', type: 'varchar(32)' }),
            col({ name: 'total_cents', type: 'bigint' }),
            col({ name: 'currency', type: 'varchar(3)' }),
            col({ name: 'shipped_at', type: 'timestamptz' }),
            col({ name: 'created_at', type: 'timestamptz' }),
          ]},
          { name: 'order_items', columns: [
            col({ name: 'id', type: 'bigint', primary_key: true }),
            col({ name: 'order_id', type: 'bigint' }),
            col({ name: 'product_id', type: 'uuid' }),
            col({ name: 'quantity', type: 'integer' }),
            col({ name: 'unit_price_cents', type: 'integer' }),
          ]},
          { name: 'customers', columns: [
            col({ name: 'id', type: 'uuid', primary_key: true }),
            col({ name: 'email', type: 'varchar(255)' }),
            col({ name: 'name', type: 'varchar(255)' }),
            col({ name: 'phone', type: 'varchar(32)' }),
            col({ name: 'country_code', type: 'varchar(2)' }),
            col({ name: 'created_at', type: 'timestamptz' }),
          ]},
          { name: 'products', columns: [
            col({ name: 'id', type: 'uuid', primary_key: true }),
            col({ name: 'sku', type: 'varchar(64)' }),
            col({ name: 'name', type: 'varchar(255)' }),
            col({ name: 'price_cents', type: 'integer' }),
            col({ name: 'inventory', type: 'integer' }),
          ]},
          { name: 'subscriptions', columns: [
            col({ name: 'id', type: 'uuid', primary_key: true }),
            col({ name: 'customer_id', type: 'uuid' }),
            col({ name: 'plan_id', type: 'varchar(64)' }),
            col({ name: 'status', type: 'varchar(32)' }),
            col({ name: 'cancelled_at', type: 'timestamptz' }),
          ]},
          { name: 'sessions', columns: [
            col({ name: 'id', type: 'uuid', primary_key: true }),
            col({ name: 'user_id', type: 'uuid' }),
            col({ name: 'expires_at', type: 'timestamptz' }),
          ]},
          { name: 'audit_events', columns: [
            col({ name: 'id', type: 'uuid', primary_key: true }),
            col({ name: 'actor_id', type: 'uuid' }),
            col({ name: 'action', type: 'varchar(100)' }),
            col({ name: 'created_at', type: 'timestamptz' }),
          ]},
          { name: 'feature_flags', columns: [
            col({ name: 'key', type: 'varchar(100)', primary_key: true }),
            col({ name: 'enabled', type: 'boolean' }),
            col({ name: 'rollout_pct', type: 'integer' }),
          ]},
          { name: 'pricing_rules', columns: [
            col({ name: 'id', type: 'uuid', primary_key: true }),
            col({ name: 'region', type: 'varchar(8)' }),
            col({ name: 'discount_pct', type: 'integer' }),
          ]},
          { name: 'promo_codes', columns: [
            col({ name: 'id', type: 'uuid', primary_key: true }),
            col({ name: 'code', type: 'varchar(64)' }),
            col({ name: 'percent_off', type: 'integer' }),
            col({ name: 'expires_at', type: 'timestamptz' }),
          ]},
        ],
      },
    ],
  };
}
