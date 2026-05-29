import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get },
}));

import { datasourceHealthKeys, fetchDatasourceHealth } from './datasourceHealth';

describe('api/datasourceHealth', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(datasourceHealthKeys.all).toEqual(['datasource-health']);
    expect(datasourceHealthKeys.list({ page: 0, size: 50 })).toEqual([
      'datasource-health',
      'list',
      { page: 0, size: 50 },
    ]);
  });

  it('fetchDatasourceHealth GETs the admin endpoint with no params by default', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 50, total_elements: 0, total_pages: 0 },
    });
    await fetchDatasourceHealth();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/datasource-health', { params: {} });
  });

  it('forwards page and size', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 1, size: 25, total_elements: 0, total_pages: 0 },
    });
    await fetchDatasourceHealth({ page: 1, size: 25 });
    expect(get).toHaveBeenCalledWith('/api/v1/admin/datasource-health', {
      params: { page: 1, size: 25 },
    });
  });

  it('returns the page envelope', async () => {
    const page = {
      content: [
        {
          datasource_id: 'd1',
          datasource_name: 'prod',
          db_type: 'POSTGRESQL',
          active: true,
          pool_active: 2,
          pool_idle: 8,
          pool_waiting: 0,
          pool_total: 10,
          pool_max: 20,
          queries_last_24h: 42,
          execution_ms_p50: 12.5,
          execution_ms_p95: 88,
          errors_last_24h: 3,
        },
      ],
      page: 0,
      size: 50,
      total_elements: 1,
      total_pages: 1,
    };
    get.mockResolvedValueOnce({ data: page });
    await expect(fetchDatasourceHealth({ page: 0, size: 50 })).resolves.toEqual(page);
  });
});
