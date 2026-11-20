import { describe, expect, it } from 'vitest';
import type { DatasourceTypeOption, DbType } from '@/types/api';
import { toCreateInput, toUpdateInput, type ConnectionFormValues } from '../connectionPayload';
import { engineFlags } from '../engineFlags';

function type(code: DbType, extra: Partial<DatasourceTypeOption> = {}): DatasourceTypeOption {
  return {
    code,
    category: 'RELATIONAL',
    display_name: code,
    icon_url: '',
    default_port: 5432,
    default_ssl_mode: 'REQUIRE',
    jdbc_url_template: '',
    driver_status: 'READY',
    bundled: true,
    source: 'bundled',
    custom_driver_id: null,
    vendor_name: null,
    driver_class: null,
    connector_id: null,
    description: null,
    documentation_url: null,
    ...extra,
  } as DatasourceTypeOption;
}

const values: ConnectionFormValues = {
  name: 'ds',
  host: 'h',
  port: 5432,
  database_name: 'db',
  jdbc_url: 'jdbc:x:y',
  dynamodb_endpoint: '',
  neo4j_bolt_uri: '',
  snowflake_url_override: '',
  bigquery_endpoint: '',
  databricks_http_path: '/sql/1.0/warehouses/a',
  local_datacenter: 'dc1',
  auth_method: 'basic',
  api_key: 'key',
  username: 'u',
  password: 'p',
  private_key_passphrase: '',
  ssl_mode: 'REQUIRE',
};

describe('engineFlags', () => {
  it('treats only a driver-backed CUSTOM type as free-form', () => {
    expect(engineFlags(type('CUSTOM', { source: 'uploaded' })).isDynamic).toBe(true);
    expect(engineFlags(type('CUSTOM', { source: 'connector' })).isDynamic).toBe(false);
    expect(engineFlags(type('SCYLLADB')).isCqlEngine).toBe(true);
  });
});

describe('toCreateInput', () => {
  it('sends host, port and database for a relational engine', () => {
    expect(toCreateInput(type('POSTGRESQL'), values)).toMatchObject({
      db_type: 'POSTGRESQL',
      host: 'h',
      port: 5432,
      database_name: 'db',
      username: 'u',
      password: 'p',
      ai_analysis_enabled: false,
    });
  });

  it('sends only the JDBC URL for a free-form driver', () => {
    const input = toCreateInput(type('CUSTOM', { source: 'uploaded' }), values);
    expect(input.jdbc_url_override).toBe('jdbc:x:y');
    expect(input.host).toBeUndefined();
  });

  it('sends the region and optional endpoint for DynamoDB', () => {
    const input = toCreateInput(type('DYNAMODB'), { ...values, dynamodb_endpoint: 'http://l:8000' });
    expect(input).toMatchObject({ database_name: 'db', jdbc_url_override: 'http://l:8000' });
    expect(toCreateInput(type('DYNAMODB'), values).jdbc_url_override).toBeUndefined();
  });

  it('sends the project and optional endpoint, and no username, for BigQuery', () => {
    const input = toCreateInput(type('BIGQUERY'), { ...values, bigquery_endpoint: 'http://e' });
    expect(input).toMatchObject({ username: '', jdbc_url_override: 'http://e' });
    expect(input.host).toBeUndefined();
  });

  it('sends the HTTP path for Databricks and the optional override for Snowflake', () => {
    expect(toCreateInput(type('DATABRICKS'), values)).toMatchObject({
      username: '',
      jdbc_url_override: '/sql/1.0/warehouses/a',
    });
    const snow = toCreateInput(type('SNOWFLAKE'), {
      ...values,
      snowflake_url_override: 'jdbc:snowflake://x',
      private_key_passphrase: 'pp',
    });
    expect(snow).toMatchObject({ jdbc_url_override: 'jdbc:snowflake://x', private_key_passphrase: 'pp' });
    expect(snow.port).toBeUndefined();
    expect(toCreateInput(type('SNOWFLAKE'), values).private_key_passphrase).toBeUndefined();
  });

  it('adds the datacenter for CQL engines and the bolt URI for Neo4j', () => {
    expect(toCreateInput(type('CASSANDRA'), values).local_datacenter).toBe('dc1');
    expect(
      toCreateInput(type('NEO4J'), { ...values, neo4j_bolt_uri: 'neo4j+s://x' }).jdbc_url_override,
    ).toBe('neo4j+s://x');
  });

  it('replaces basic credentials with the API key on a search engine', () => {
    const input = toCreateInput(type('ELASTICSEARCH'), { ...values, auth_method: 'api_key' });
    expect(input).toMatchObject({ username: '', password: '', api_key: 'key' });
    expect(toCreateInput(type('ELASTICSEARCH'), values).api_key).toBeUndefined();
  });
});

describe('toUpdateInput', () => {
  it('sends the connection fields without the type', () => {
    const input = toUpdateInput(type('POSTGRESQL'), values);
    expect(input).toMatchObject({ name: 'ds', host: 'h', port: 5432, database_name: 'db' });
    expect(input).not.toHaveProperty('db_type');
  });

  it('clears optional overrides with a blank value', () => {
    expect(toUpdateInput(type('DYNAMODB'), values).jdbc_url_override).toBe('');
    expect(toUpdateInput(type('BIGQUERY'), values).jdbc_url_override).toBe('');
    expect(toUpdateInput(type('NEO4J'), values).jdbc_url_override).toBe('');
    expect(toUpdateInput(type('SNOWFLAKE'), values)).toMatchObject({
      jdbc_url_override: '',
      private_key_passphrase: '',
    });
    expect(toUpdateInput(type('ELASTICSEARCH'), values).api_key).toBe('');
  });

  it('keeps the engine-specific values', () => {
    expect(toUpdateInput(type('DATABRICKS'), values).jdbc_url_override).toBe('/sql/1.0/warehouses/a');
    expect(toUpdateInput(type('SCYLLADB'), values).local_datacenter).toBe('dc1');
    expect(toUpdateInput(type('CUSTOM', { source: 'uploaded' }), values).jdbc_url_override).toBe(
      'jdbc:x:y',
    );
    expect(
      toUpdateInput(type('OPENSEARCH'), { ...values, auth_method: 'api_key' }).api_key,
    ).toBe('key');
  });
});
