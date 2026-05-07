import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, delete: del },
}));

import * as queriesApi from './queries';

describe('api/queries', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
  });

  it('submitQuery posts to /api/v1/queries', async () => {
    post.mockResolvedValueOnce({
      data: { id: 'q-1', status: 'PENDING_AI', ai_analysis: null, review_plan: null,
        estimated_review_completion: null },
    });
    const result = await queriesApi.submitQuery({
      datasource_id: 'ds-1',
      sql: 'SELECT 1',
      justification: 'why',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/queries', {
      datasource_id: 'ds-1',
      sql: 'SELECT 1',
      justification: 'why',
    });
    expect(result.id).toBe('q-1');
    expect(result.status).toBe('PENDING_AI');
  });

  it('listQueries maps filter fields to camelCase query params', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0, last: true },
    });
    await queriesApi.listQueries({
      status: 'PENDING_REVIEW',
      datasource_id: 'ds-1',
      submitted_by: 'user-1',
      query_type: 'SELECT',
      from: '2026-01-01T00:00:00Z',
      to: '2026-02-01T00:00:00Z',
      page: 2,
      size: 50,
    });
    expect(get).toHaveBeenCalledWith('/api/v1/queries', {
      params: {
        status: 'PENDING_REVIEW',
        datasourceId: 'ds-1',
        submittedBy: 'user-1',
        queryType: 'SELECT',
        from: '2026-01-01T00:00:00Z',
        to: '2026-02-01T00:00:00Z',
        page: 2,
        size: 50,
      },
    });
  });

  it('listQueries omits undefined filters', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0, last: true },
    });
    await queriesApi.listQueries();
    expect(get).toHaveBeenCalledWith('/api/v1/queries', { params: {} });
  });

  it('getQuery hits /api/v1/queries/{id}', async () => {
    get.mockResolvedValueOnce({ data: { id: 'q-1' } });
    const result = await queriesApi.getQuery('q-1');
    expect(get).toHaveBeenCalledWith('/api/v1/queries/q-1');
    expect(result).toEqual({ id: 'q-1' });
  });

  it('cancelQuery DELETEs /api/v1/queries/{id}', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await queriesApi.cancelQuery('q-1');
    expect(del).toHaveBeenCalledWith('/api/v1/queries/q-1');
  });

  it('executeQuery POSTs /api/v1/queries/{id}/execute', async () => {
    post.mockResolvedValueOnce({
      data: { id: 'q-1', status: 'EXECUTED', rows_affected: 42, duration_ms: 100 },
    });
    const result = await queriesApi.executeQuery('q-1');
    expect(post).toHaveBeenCalledWith('/api/v1/queries/q-1/execute');
    expect(result.rows_affected).toBe(42);
  });

  it('getQueryResults sends page+size query params', async () => {
    get.mockResolvedValueOnce({
      data: { columns: [], rows: [], row_count: 0, truncated: false, page: 1, size: 25 },
    });
    await queriesApi.getQueryResults('q-1', 1, 25);
    expect(get).toHaveBeenCalledWith('/api/v1/queries/q-1/results', {
      params: { page: 1, size: 25 },
    });
  });

  it('analyzeOnly POSTs /api/v1/queries/analyze', async () => {
    post.mockResolvedValueOnce({
      data: { risk_level: 'LOW', risk_score: 10, summary: 'ok', issues: [] },
    });
    await queriesApi.analyzeOnly({ datasource_id: 'ds-1', sql: 'SELECT 1' });
    expect(post).toHaveBeenCalledWith('/api/v1/queries/analyze', {
      datasource_id: 'ds-1',
      sql: 'SELECT 1',
    });
  });

  it('isPending matches PENDING_* statuses', () => {
    expect(queriesApi.isPending('PENDING_AI')).toBe(true);
    expect(queriesApi.isPending('PENDING_REVIEW')).toBe(true);
    expect(queriesApi.isPending('APPROVED')).toBe(false);
    expect(queriesApi.isPending('EXECUTED')).toBe(false);
  });

  it('queryKeys produce stable factory output', () => {
    expect(queriesApi.queryKeys.detail('q-1')).toEqual(['queries', 'detail', 'q-1']);
    expect(queriesApi.queryKeys.results('q-1', 0, 25))
      .toEqual(['queries', 'detail', 'q-1', 'results', 0, 25]);
    expect(queriesApi.queryKeys.list({ status: 'EXECUTED', size: 10 }))
      .toEqual(['queries', 'list', { status: 'EXECUTED', size: 10 }]);
  });
});
