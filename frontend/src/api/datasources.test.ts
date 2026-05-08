import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put, delete: del },
}));

import * as datasourcesApi from './datasources';

const datasourceFixture = {
  id: 'ds-1',
  organization_id: 'org-1',
  name: 'Production',
  db_type: 'POSTGRESQL',
  host: 'db.example.com',
  port: 5432,
  database_name: 'app',
  username: 'svc',
  ssl_mode: 'VERIFY_FULL',
  connection_pool_size: 10,
  max_rows_per_query: 1000,
  require_review_reads: false,
  require_review_writes: true,
  review_plan_id: 'rp-1',
  ai_analysis_enabled: true,
  active: true,
  created_at: '2026-05-04T10:15:00Z',
};

const permissionFixture = {
  id: 'perm-1',
  datasource_id: 'ds-1',
  user_id: 'u-1',
  user_email: 'a@example.com',
  user_display_name: 'Alice',
  can_read: true,
  can_write: false,
  can_ddl: false,
  row_limit_override: null,
  allowed_schemas: null,
  allowed_tables: null,
  expires_at: null,
  created_by: 'u-admin',
  created_at: '2026-05-04T10:15:00Z',
};

describe('api/datasources', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('listDatasources GETs /api/v1/datasources with no params by default', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0, last: true },
    });
    await datasourcesApi.listDatasources();
    expect(get).toHaveBeenCalledWith('/api/v1/datasources', { params: {} });
  });

  it('listDatasources forwards page and size', async () => {
    get.mockResolvedValueOnce({
      data: { content: [datasourceFixture], page: 1, size: 50, total_elements: 60, total_pages: 2, last: false },
    });
    const result = await datasourcesApi.listDatasources({ page: 1, size: 50 });
    expect(get).toHaveBeenCalledWith('/api/v1/datasources', { params: { page: 1, size: 50 } });
    expect(result.content).toHaveLength(1);
    expect(result.total_elements).toBe(60);
  });

  it('getDatasource GETs /api/v1/datasources/{id}', async () => {
    get.mockResolvedValueOnce({ data: datasourceFixture });
    const result = await datasourcesApi.getDatasource('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1');
    expect(result.id).toBe('ds-1');
  });

  it('createDatasource POSTs /api/v1/datasources with the snake_case body', async () => {
    post.mockResolvedValueOnce({ data: datasourceFixture });
    const input = {
      name: 'New',
      db_type: 'POSTGRESQL' as const,
      host: 'db',
      port: 5432,
      database_name: 'app',
      username: 'svc',
      password: 'pw',
      ssl_mode: 'REQUIRE' as const,
      connection_pool_size: 10,
      max_rows_per_query: 1000,
      require_review_reads: false,
      require_review_writes: true,
      review_plan_id: null,
      ai_analysis_enabled: true,
    };
    await datasourcesApi.createDatasource(input);
    expect(post).toHaveBeenCalledWith('/api/v1/datasources', input);
  });

  it('updateDatasource PUTs /api/v1/datasources/{id} with the body', async () => {
    put.mockResolvedValueOnce({ data: datasourceFixture });
    await datasourcesApi.updateDatasource('ds-1', { name: 'Renamed', max_rows_per_query: 500 });
    expect(put).toHaveBeenCalledWith('/api/v1/datasources/ds-1', {
      name: 'Renamed',
      max_rows_per_query: 500,
    });
  });

  it('deleteDatasource DELETEs /api/v1/datasources/{id}', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await datasourcesApi.deleteDatasource('ds-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1');
  });

  it('testConnection POSTs /api/v1/datasources/{id}/test', async () => {
    post.mockResolvedValueOnce({ data: { ok: true, latency_ms: 84, message: null } });
    const result = await datasourcesApi.testConnection('ds-1');
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/ds-1/test');
    expect(result.ok).toBe(true);
    expect(result.latency_ms).toBe(84);
  });

  it('getDatasourceSchema GETs /api/v1/datasources/{id}/schema', async () => {
    const schema = {
      schemas: [
        {
          name: 'public',
          tables: [
            {
              name: 'users',
              columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
            },
          ],
        },
      ],
    };
    get.mockResolvedValueOnce({ data: schema });
    const result = await datasourcesApi.getDatasourceSchema('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/schema');
    expect(result.schemas[0]!.tables[0]!.columns[0]!.primary_key).toBe(true);
  });

  it('listPermissions unwraps the { content } envelope', async () => {
    get.mockResolvedValueOnce({ data: { content: [permissionFixture] } });
    const result = await datasourcesApi.listPermissions('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/permissions');
    expect(result).toEqual([permissionFixture]);
  });

  it('grantPermission POSTs /api/v1/datasources/{id}/permissions', async () => {
    post.mockResolvedValueOnce({ data: permissionFixture });
    const input = {
      user_id: 'u-1',
      can_read: true,
      can_write: false,
      can_ddl: false,
    };
    const result = await datasourcesApi.grantPermission('ds-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/ds-1/permissions', input);
    expect(result.id).toBe('perm-1');
  });

  it('revokePermission DELETEs /api/v1/datasources/{id}/permissions/{permId}', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await datasourcesApi.revokePermission('ds-1', 'perm-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1/permissions/perm-1');
  });

  it('getDatasourceTypes GETs /api/v1/datasources/types', async () => {
    get.mockResolvedValueOnce({ data: { types: [] } });
    const result = await datasourcesApi.getDatasourceTypes();
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/types');
    expect(result.types).toEqual([]);
  });

  it('datasourceKeys produce stable factory output', () => {
    expect(datasourcesApi.datasourceKeys.detail('ds-1'))
      .toEqual(['datasources', 'detail', 'ds-1']);
    expect(datasourcesApi.datasourceKeys.schema('ds-1'))
      .toEqual(['datasources', 'detail', 'ds-1', 'schema']);
    expect(datasourcesApi.datasourceKeys.permissions('ds-1'))
      .toEqual(['datasources', 'detail', 'ds-1', 'permissions']);
    expect(datasourcesApi.datasourceKeys.list({ page: 0, size: 100 }))
      .toEqual(['datasources', 'list', { page: 0, size: 100 }]);
    expect(datasourcesApi.datasourceKeys.types())
      .toEqual(['datasources', 'types']);
  });
});
