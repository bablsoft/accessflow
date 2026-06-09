import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as connectorsApi from './connectors';

const fixture = {
  id: 'clickhouse',
  db_type: 'CUSTOM',
  name: 'ClickHouse',
  icon_url: '/db-icons/clickhouse.svg',
  vendor: 'ClickHouse, Inc.',
  description: 'Column-oriented OLAP database.',
  documentation_url: 'https://clickhouse.com/docs/integrations/java',
  default_port: 8123,
  default_ssl_mode: 'DISABLE',
  jdbc_url_template: 'jdbc:ch://{host}:{port}/{database_name}',
  driver_class: 'com.clickhouse.jdbc.ClickHouseDriver',
  driver_status: 'AVAILABLE',
  bundled: false,
};

describe('connectors API', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('listConnectors unwraps the connectors array', async () => {
    get.mockResolvedValueOnce({ data: { connectors: [fixture] } });
    const result = await connectorsApi.listConnectors();
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/connectors');
    expect(result).toEqual([fixture]);
  });

  it('installConnector posts to the install path and returns the connector', async () => {
    post.mockResolvedValueOnce({ data: { ...fixture, driver_status: 'READY' } });
    const result = await connectorsApi.installConnector('clickhouse');
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/connectors/clickhouse/install');
    expect(result.driver_status).toBe('READY');
  });

  it('connectorKeys returns a stable hierarchy', () => {
    expect(connectorsApi.connectorKeys.all).toEqual(['connectors']);
    expect(connectorsApi.connectorKeys.lists()).toEqual(['connectors', 'list']);
  });
});
