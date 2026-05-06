import type { Datasource, DatasourceSchema } from '@/types/api';

export function buildMockSchema(ds: Datasource): DatasourceSchema {
  const isPg = ds.db_type === 'POSTGRESQL';
  return {
    schemas: [
      {
        name: 'public',
        tables: [
          { name: 'users', columns: [
            { name: 'id', type: isPg ? 'uuid' : 'binary(16)', primary_key: true },
            { name: 'email', type: 'varchar(255)' },
            { name: 'display_name', type: 'varchar(255)' },
            { name: 'password_hash', type: 'varchar(255)' },
            { name: 'role', type: 'varchar(32)' },
            { name: 'is_active', type: 'boolean' },
            { name: 'last_login_at', type: 'timestamptz' },
            { name: 'created_at', type: 'timestamptz' },
          ]},
          { name: 'orders', columns: [
            { name: 'id', type: 'bigint', primary_key: true },
            { name: 'customer_id', type: 'uuid' },
            { name: 'status', type: 'varchar(32)' },
            { name: 'total_cents', type: 'bigint' },
            { name: 'currency', type: 'varchar(3)' },
            { name: 'shipped_at', type: 'timestamptz' },
            { name: 'created_at', type: 'timestamptz' },
          ]},
          { name: 'order_items', columns: [
            { name: 'id', type: 'bigint', primary_key: true },
            { name: 'order_id', type: 'bigint' },
            { name: 'product_id', type: 'uuid' },
            { name: 'quantity', type: 'integer' },
            { name: 'unit_price_cents', type: 'integer' },
          ]},
          { name: 'customers', columns: [
            { name: 'id', type: 'uuid', primary_key: true },
            { name: 'email', type: 'varchar(255)' },
            { name: 'name', type: 'varchar(255)' },
            { name: 'phone', type: 'varchar(32)' },
            { name: 'country_code', type: 'varchar(2)' },
            { name: 'created_at', type: 'timestamptz' },
          ]},
          { name: 'products', columns: [
            { name: 'id', type: 'uuid', primary_key: true },
            { name: 'sku', type: 'varchar(64)' },
            { name: 'name', type: 'varchar(255)' },
            { name: 'price_cents', type: 'integer' },
            { name: 'inventory', type: 'integer' },
          ]},
          { name: 'subscriptions', columns: [
            { name: 'id', type: 'uuid', primary_key: true },
            { name: 'customer_id', type: 'uuid' },
            { name: 'plan_id', type: 'varchar(64)' },
            { name: 'status', type: 'varchar(32)' },
            { name: 'cancelled_at', type: 'timestamptz' },
          ]},
          { name: 'sessions', columns: [
            { name: 'id', type: 'uuid', primary_key: true },
            { name: 'user_id', type: 'uuid' },
            { name: 'expires_at', type: 'timestamptz' },
          ]},
          { name: 'audit_events', columns: [
            { name: 'id', type: 'uuid', primary_key: true },
            { name: 'actor_id', type: 'uuid' },
            { name: 'action', type: 'varchar(100)' },
            { name: 'created_at', type: 'timestamptz' },
          ]},
          { name: 'feature_flags', columns: [
            { name: 'key', type: 'varchar(100)', primary_key: true },
            { name: 'enabled', type: 'boolean' },
            { name: 'rollout_pct', type: 'integer' },
          ]},
          { name: 'pricing_rules', columns: [
            { name: 'id', type: 'uuid', primary_key: true },
            { name: 'region', type: 'varchar(8)' },
            { name: 'discount_pct', type: 'integer' },
          ]},
          { name: 'promo_codes', columns: [
            { name: 'id', type: 'uuid', primary_key: true },
            { name: 'code', type: 'varchar(64)' },
            { name: 'percent_off', type: 'integer' },
            { name: 'expires_at', type: 'timestamptz' },
          ]},
        ],
      },
    ],
  };
}
