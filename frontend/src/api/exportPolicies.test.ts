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

import * as exportPolicyApi from './exportPolicies';
import { exportPolicyKeys } from './exportPolicies';

const policyFixture = {
  id: 'ep-1',
  datasource_id: 'ds-1',
  mode: 'ROW_CAP' as const,
  row_cap: 1000,
  deny_classifications: [],
  applies_to_roles: ['ANALYST'],
  applies_to_group_ids: [],
  applies_to_user_ids: [],
  enabled: true,
  created_at: '2026-08-01T10:00:00Z',
  updated_at: '2026-08-01T10:00:00Z',
};

describe('api/exportPolicies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(exportPolicyKeys.list('ds-1')).toEqual(['export-policies', 'list', 'ds-1']);
  });

  it('listExportPolicies GETs the datasource-scoped path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [policyFixture] } });
    const result = await exportPolicyApi.listExportPolicies('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/export-policies');
    expect(result).toHaveLength(1);
    expect(result[0]?.mode).toBe('ROW_CAP');
  });

  it('createExportPolicy POSTs the snake_case body', async () => {
    post.mockResolvedValueOnce({ data: policyFixture });
    const input = {
      mode: 'ROW_CAP' as const,
      row_cap: 1000,
      applies_to_roles: ['ANALYST'],
      enabled: true,
    };
    await exportPolicyApi.createExportPolicy('ds-1', input);
    expect(post.mock.calls[0]?.[0]).toBe('/api/v1/datasources/ds-1/export-policies');
    expect(post.mock.calls[0]?.[1]).toEqual(input);
  });

  it('updateExportPolicy PUTs to the policy path', async () => {
    put.mockResolvedValueOnce({ data: policyFixture });
    await exportPolicyApi.updateExportPolicy('ds-1', 'ep-1', {
      mode: 'WATERMARK',
      enabled: false,
    });
    expect(put.mock.calls[0]?.[0]).toBe('/api/v1/datasources/ds-1/export-policies/ep-1');
  });

  it('deleteExportPolicy DELETEs the policy path', async () => {
    del.mockResolvedValueOnce({});
    await exportPolicyApi.deleteExportPolicy('ds-1', 'ep-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1/export-policies/ep-1');
  });
});
