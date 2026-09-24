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

import * as rowLimitApi from './rowLimitPolicies';
import { rowLimitPolicyKeys } from './rowLimitPolicies';

const policyFixture = {
  id: 'rlp-1',
  datasource_id: 'ds-1',
  schema_name: 'crm',
  table_name: 'customer',
  max_rows: 200,
  applies_to_roles: ['ANALYST'],
  applies_to_group_ids: [],
  applies_to_user_ids: [],
  enabled: true,
  created_at: '2026-09-01T10:00:00Z',
  updated_at: '2026-09-01T10:00:00Z',
};

describe('api/rowLimitPolicies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(rowLimitPolicyKeys.list('ds-1')).toEqual(['row-limit-policies', 'list', 'ds-1']);
  });

  it('listRowLimitPolicies GETs the datasource-scoped path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [policyFixture] } });
    const result = await rowLimitApi.listRowLimitPolicies('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-limit-policies');
    expect(result[0]?.max_rows).toBe(200);
  });

  it('createRowLimitPolicy POSTs the snake_case body', async () => {
    post.mockResolvedValueOnce({ data: policyFixture });
    const input = { schema_name: 'crm', table_name: 'customer', max_rows: 200 };
    await rowLimitApi.createRowLimitPolicy('ds-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-limit-policies', input);
  });

  it('updateRowLimitPolicy PUTs to the policy path', async () => {
    put.mockResolvedValueOnce({ data: policyFixture });
    const input = { table_name: 'orders', max_rows: 5, enabled: false };
    await rowLimitApi.updateRowLimitPolicy('ds-1', 'rlp-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-limit-policies/rlp-1', input);
  });

  it('deleteRowLimitPolicy DELETEs the policy path', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await rowLimitApi.deleteRowLimitPolicy('ds-1', 'rlp-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-limit-policies/rlp-1');
  });
});
