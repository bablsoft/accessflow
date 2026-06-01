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

import * as maskingApi from './maskingPolicies';
import { maskingPolicyKeys } from './maskingPolicies';

const policyFixture = {
  id: 'mp-1',
  datasource_id: 'ds-1',
  column_ref: 'public.users.email',
  strategy: 'PARTIAL' as const,
  strategy_params: { visible_suffix: '4' },
  reveal_to_roles: ['ADMIN'] as const,
  reveal_to_group_ids: [],
  reveal_to_user_ids: [],
  enabled: true,
  created_at: '2026-06-01T10:00:00Z',
  updated_at: '2026-06-01T10:00:00Z',
};

describe('api/maskingPolicies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(maskingPolicyKeys.list('ds-1')).toEqual(['masking-policies', 'list', 'ds-1']);
  });

  it('listMaskingPolicies GETs the datasource-scoped path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [policyFixture] } });
    const result = await maskingApi.listMaskingPolicies('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/masking-policies');
    expect(result).toHaveLength(1);
    expect(result[0]?.column_ref).toBe('public.users.email');
  });

  it('createMaskingPolicy POSTs the snake_case body', async () => {
    post.mockResolvedValueOnce({ data: policyFixture });
    const input = {
      column_ref: 'public.users.email',
      strategy: 'PARTIAL' as const,
      strategy_params: { visible_suffix: '4' },
      reveal_to_roles: ['ADMIN'],
      reveal_to_user_ids: ['u-1'],
    };
    await maskingApi.createMaskingPolicy('ds-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/ds-1/masking-policies', input);
  });

  it('updateMaskingPolicy PUTs to the policy path', async () => {
    put.mockResolvedValueOnce({ data: policyFixture });
    const input = { column_ref: 'public.users.ssn', strategy: 'HASH' as const };
    await maskingApi.updateMaskingPolicy('ds-1', 'mp-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/datasources/ds-1/masking-policies/mp-1', input);
  });

  it('deleteMaskingPolicy DELETEs the policy path', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await maskingApi.deleteMaskingPolicy('ds-1', 'mp-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1/masking-policies/mp-1');
  });
});
